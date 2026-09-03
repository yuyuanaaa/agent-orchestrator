package com.kanodays88.skytakeoutai.tools;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.kanodays88.skytakeoutai.entity.Category;
import com.kanodays88.skytakeoutai.entity.Setmeal;
import com.kanodays88.skytakeoutai.entity.SetmealDish;
import com.kanodays88.skytakeoutai.entity.querys.SetmealQuery;
import com.kanodays88.skytakeoutai.entity.vo.SetmealVO;
import com.kanodays88.skytakeoutai.service.CategoryService;
import com.kanodays88.skytakeoutai.service.SetmealDishService;
import com.kanodays88.skytakeoutai.service.SetmealService;
import com.kanodays88.skytakeoutai.utils.HttpPathUtil;
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
import java.util.List;
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

    private static final String SETMEAL_CACHE_KEY_PREFIX = "setmeal:query:";
    private static final String SETMEAL_LOCK_KEY_PREFIX = "lock:setmeal:query:";
    private static final long CACHE_TTL_MINUTES = 30;
    private static final long EMPTY_CACHE_TTL_MINUTES = 2;

    @Tool(description = "外卖平台查询套餐工具")
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
        List<SetmealVO> setmealVOS = new ArrayList<>();
        for (Setmeal s : list) {
            SetmealVO setmealVO = new SetmealVO();
            BeanUtil.copyProperties(s, setmealVO);
            List<Category> categories = categoryServiceImpl.query().select("name").eq("id", s.getCategoryId()).list();
            if (categories != null && !categories.isEmpty()) {
                setmealVO.setCategoryName(categories.get(0).getName());
            }
            List<SetmealDish> setmealDishes = setmealDishServiceImpl.query().select("name").eq("setmeal_id", s.getId()).list();
            setmealVO.setDishesName(setmealDishes.stream().map(SetmealDish::getName).toList());
            // 拼接图片 URL —— 缓存的是拼好 URL 的最终结果，避免缓存与返回值不一致
            setmealVO.setImage(HttpPathUtil.writeHttpUrl("/upload/" + setmealVO.getImage()));
            setmealVOS.add(setmealVO);
        }
        return setmealVOS;
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
