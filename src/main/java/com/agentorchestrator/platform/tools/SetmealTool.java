package com.agentorchestrator.platform.tools;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.agentorchestrator.platform.cache.MenuCacheService;
import com.agentorchestrator.platform.entity.Category;
import com.agentorchestrator.platform.entity.Setmeal;
import com.agentorchestrator.platform.entity.SetmealDish;
import com.agentorchestrator.platform.entity.querys.SetmealQuery;
import com.agentorchestrator.platform.entity.vo.SetmealVO;
import com.agentorchestrator.platform.service.CategoryService;
import com.agentorchestrator.platform.service.SetmealDishService;
import com.agentorchestrator.platform.service.SetmealService;
import com.agentorchestrator.platform.utils.HttpPathUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 套餐查询工具。缓存策略与 {@link DishTool} 一致：
 * Cache-Aside + 缓存 key 级分布式锁 + TTL + 空结果短缓存防穿透 + 抢锁超时降级查库。
 */
@Component
@Slf4j
public class SetmealTool {

    @Autowired
    private SetmealService setmealServiceImpl;

    @Autowired
    private CategoryService categoryServiceImpl;

    @Autowired
    private SetmealDishService setmealDishServiceImpl;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 缓存 key 前缀统一取自 {@link MenuCacheService}，保证读路径（本类）
     * 与管理端写路径的失效逻辑命中同一批 key，避免两边各写一份常量而失配。
     */
    private static final String SETMEAL_CACHE_KEY_PREFIX = MenuCacheService.SETMEAL_CACHE_KEY_PREFIX;
    private static final String SETMEAL_LOCK_KEY_PREFIX = "lock:setmeal:query:";
    private static final long CACHE_TTL_MINUTES = 30;
    private static final long EMPTY_CACHE_TTL_MINUTES = 2;

    @Tool(description = "商家服务平台查询套餐工具")
    public List<SetmealVO> querySetmeal(@ToolParam(description = "查询套餐的条件") SetmealQuery setmealQuery) {
        String key = generateCacheKey(setmealQuery);
        return getCache(key, setmealQuery);
    }

    private @NonNull List<SetmealVO> getSetmealVOS(SetmealQuery setmealQuery) {
        QueryChainWrapper<Setmeal> query = setmealServiceImpl.query();
        // 套餐名称条件
        if (setmealQuery.getSetmealNames() != null && !setmealQuery.getSetmealNames().isEmpty()) {
            query.in("name", setmealQuery.getSetmealNames());
        }
        // 分类条件
        if (setmealQuery.getCategory() != null && !setmealQuery.getCategory().isEmpty()) {
            List<Category> categories = categoryServiceImpl.query().select("id").in("name", setmealQuery.getCategory()).list();
            if (categories != null && !categories.isEmpty()) {
                List<Long> categoriesId = categories.stream().map(Category::getId).toList();
                query.in("category_id", categoriesId);
            }
        }
        // 菜品名称条件：先查包含这些菜品的套餐 id，再按 id 查套餐
        if (setmealQuery.getDishNames() != null && !setmealQuery.getDishNames().isEmpty()) {
            QueryWrapper<SetmealDish> queryWrapper = new QueryWrapper<>();
            queryWrapper.select("DISTINCT setmeal_id").in("name", setmealQuery.getDishNames());
            List<SetmealDish> setmealDishList = setmealDishServiceImpl.list(queryWrapper);
            List<Long> setmealIdList = setmealDishList.stream()
                    .map(SetmealDish::getSetmealId)
                    .collect(Collectors.toList());
            query.in("id", setmealIdList);
        }

        if (setmealQuery.getMinPrice() != null) {
            query.ge("price", setmealQuery.getMinPrice());
        }
        if (setmealQuery.getMaxPrice() != null) {
            query.le("price", setmealQuery.getMaxPrice());
        }

        List<Setmeal> list = query.list();
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        // 批量预加载分类名与套餐所含菜品名，避免逐条查询造成的两重 N+1
        Map<Long, String> categoryNameMap = loadCategoryNameMap(list);
        Map<Long, List<String>> setmealDishesMap = loadSetmealDishesMap(list);

        List<SetmealVO> setmealVOS = new ArrayList<>(list.size());
        for (Setmeal s : list) {
            SetmealVO setmealVO = new SetmealVO();
            BeanUtil.copyProperties(s, setmealVO);
            setmealVO.setCategoryName(categoryNameMap.get(s.getCategoryId()));
            setmealVO.setDishesName(setmealDishesMap.getOrDefault(s.getId(), List.of()));
            // 拼接图片 URL —— 缓存的是拼好 URL 的最终结果，避免缓存与返回值不一致
            setmealVO.setImage(toFullUrl(setmealVO.getImage()));
            setmealVOS.add(setmealVO);
        }
        return setmealVOS;
    }

    /**
     * 相对路径拼完整 URL：null/空白/已含 http 原样返回，否则拼 /upload/ 前缀。
     * <p>
     * 与 {@code AdminController.toFullUrl} 语义保持一致，避免无图套餐被拼成
     * {@code /upload/null} 导致前端展示破图；同时支持数据库中直接存完整外链的图片。
     */
    private String toFullUrl(String image) {
        if (image == null || image.isBlank() || image.startsWith("http")) {
            return image;
        }
        return HttpPathUtil.writeHttpUrl("/upload/" + image);
    }

    /**
     * 批量查出本次结果集涉及的分类名，构建 id -> name 映射。
     * <p>
     * 原实现在装配 VO 的循环里逐条 {@code eq("id", categoryId)} 查询（第一重 N+1）；
     * 改为 categoryId 去重后单条 IN 查询，DB 往返与结果集大小无关。
     */
    private Map<Long, String> loadCategoryNameMap(List<Setmeal> setmeals) {
        List<Long> categoryIds = setmeals.stream()
                .map(Setmeal::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        List<Category> categories = categoryServiceImpl.query()
                .select("id", "name")
                .in("id", categoryIds)
                .list();
        Map<Long, String> nameMap = new HashMap<>();
        if (categories != null) {
            for (Category c : categories) {
                nameMap.put(c.getId(), c.getName());
            }
        }
        return nameMap;
    }

    /**
     * 批量查出本次结果集涉及的套餐-菜品关联，构建 setmealId -> 菜品名列表 映射。
     * <p>
     * 原实现每个套餐各查一次 setmeal_dish（第二重 N+1）；
     * 改为按 setmealId 集合单条 IN 查询后在内存分组。
     */
    private Map<Long, List<String>> loadSetmealDishesMap(List<Setmeal> setmeals) {
        List<Long> setmealIds = setmeals.stream()
                .map(Setmeal::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (setmealIds.isEmpty()) {
            return Map.of();
        }
        List<SetmealDish> dishes = setmealDishServiceImpl.query()
                .select("setmeal_id", "name")
                .in("setmeal_id", setmealIds)
                .list();
        Map<Long, List<String>> dishesMap = new HashMap<>();
        if (dishes != null) {
            for (SetmealDish sd : dishes) {
                dishesMap.computeIfAbsent(sd.getSetmealId(), k -> new ArrayList<>()).add(sd.getName());
            }
        }
        return dishesMap;
    }

    private List<SetmealVO> getCache(String key, SetmealQuery setmealQuery) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            return JSONUtil.toList(json, SetmealVO.class);
        }

        RLock lock = redissonClient.getLock(SETMEAL_LOCK_KEY_PREFIX + key);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (locked) {
                // 双重检查：拿到锁后再查一次缓存
                json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    return JSONUtil.toList(json, SetmealVO.class);
                }
                List<SetmealVO> result = getSetmealVOS(setmealQuery);
                long ttl = result.isEmpty() ? EMPTY_CACHE_TTL_MINUTES : CACHE_TTL_MINUTES;
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result), ttl, TimeUnit.MINUTES);
                return result;
            }
            log.warn("套餐查询抢锁超时，降级直接查库, key={}", key);
            return getSetmealVOS(setmealQuery);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("操作被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 生成唯一缓存 Key：各条件排序后拼接，保证语义相同的查询命中同一缓存 */
    private String generateCacheKey(SetmealQuery query) {
        StringBuilder sb = new StringBuilder(SETMEAL_CACHE_KEY_PREFIX);

        if (query.getCategory() != null && !query.getCategory().isEmpty()) {
            List<String> sortedCats = new ArrayList<>(query.getCategory());
            Collections.sort(sortedCats);
            sb.append("c:").append(String.join("|", sortedCats));
        }

        sb.append("p:")
                .append(query.getMinPrice() != null ? query.getMinPrice() : "NULL")
                .append("-")
                .append(query.getMaxPrice() != null ? query.getMaxPrice() : "NULL");

        if (query.getDishNames() != null && !query.getDishNames().isEmpty()) {
            List<String> sortedDishes = new ArrayList<>(query.getDishNames());
            Collections.sort(sortedDishes);
            sb.append("d:").append(String.join("|", sortedDishes));
        }

        if (query.getSetmealNames() != null && !query.getSetmealNames().isEmpty()) {
            List<String> sortedSetmeals = new ArrayList<>(query.getSetmealNames());
            Collections.sort(sortedSetmeals);
            sb.append("s:").append(String.join("|", sortedSetmeals));
        }

        return sb.toString();
    }
}
