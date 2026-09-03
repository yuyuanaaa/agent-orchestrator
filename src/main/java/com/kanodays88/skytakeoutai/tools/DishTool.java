package com.kanodays88.skytakeoutai.tools;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.kanodays88.skytakeoutai.entity.Category;
import com.kanodays88.skytakeoutai.entity.Dish;
import com.kanodays88.skytakeoutai.entity.querys.DishQuery;
import com.kanodays88.skytakeoutai.entity.vo.DishVO;
import com.kanodays88.skytakeoutai.service.CategoryService;
import com.kanodays88.skytakeoutai.service.DishService;
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
 * 菜品查询工具。
 * <p>
 * 缓存设计（Cache-Aside + 分布式锁防击穿）：
 * <ul>
 *   <li>缓存的是<b>拼接图片 URL 之后</b>的最终结果，保证缓存与返回值一致</li>
 *   <li>锁粒度为缓存 key 级别（而非全局锁），不同查询条件互不阻塞</li>
 *   <li>缓存带 TTL（默认 30 分钟），空结果也缓存（短 TTL）防穿透</li>
 *   <li>抢锁失败时不返回 null，而是降级为直接查库，保证可用性</li>
 * </ul>
 */
@Component
@Slf4j
public class DishTool {

    @Autowired
    private DishService dishServiceImpl;

    @Autowired
    private CategoryService categoryServiceImpl;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String DISH_CACHE_KEY_PREFIX = "dish:query:";
    private static final String DISH_LOCK_KEY_PREFIX = "lock:dish:query:";
    /** 命中缓存有效期 */
    private static final long CACHE_TTL_MINUTES = 30;
    /** 空结果缓存有效期（防穿透） */
    private static final long EMPTY_CACHE_TTL_MINUTES = 2;

    @Tool(description = "外卖平台查询菜品工具")
    public List<DishVO> queryDish(@ToolParam(description = "查询菜品的条件") DishQuery dishQuery) {
        String key = generateCacheKey(dishQuery);
        return getCache(key, dishQuery);
    }

    private @NonNull List<DishVO> getDishVOS(DishQuery dishQuery) {
        QueryChainWrapper<Dish> query = dishServiceImpl.query();
        if (dishQuery.getDishNames() != null && !dishQuery.getDishNames().isEmpty()) {
            query.in("name", dishQuery.getDishNames());
        }
        if (dishQuery.getCategory() != null && !dishQuery.getCategory().isEmpty()) {
            List<Category> categories = categoryServiceImpl.query().select("id").in("name", dishQuery.getCategory()).list();
            if (categories != null && !categories.isEmpty()) {
                List<Long> categoriesId = categories.stream().map(Category::getId).toList();
                query.in("category_id", categoriesId);
            }
        }
        if (dishQuery.getMinPrice() != null) {
            query.ge("price", dishQuery.getMinPrice());
        }
        if (dishQuery.getMaxPrice() != null) {
            query.le("price", dishQuery.getMaxPrice());
        }

        List<Dish> list = query.list();

        List<DishVO> dishVOS = new ArrayList<>();
        for (Dish d : list) {
            DishVO dishVO = new DishVO();
            BeanUtil.copyProperties(d, dishVO);
            List<Category> categories = categoryServiceImpl.query().select("name").eq("id", d.getCategoryId()).list();
            if (categories != null && !categories.isEmpty()) {
                dishVO.setCategoryName(categories.get(0).getName());
            }
            // 拼接图片 URL —— 缓存的是拼好 URL 的最终结果，避免缓存与返回值不一致
            dishVO.setImage(HttpPathUtil.writeHttpUrl("/upload/" + dishVO.getImage()));
            dishVOS.add(dishVO);
        }
        return dishVOS;
    }

    private List<DishVO> getCache(String key, DishQuery dishQuery) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            return JSONUtil.toList(json, DishVO.class);
        }

        // 缓存未命中：按缓存 key 加锁回源，只串行化同一查询条件，不同条件互不影响
        RLock lock = redissonClient.getLock(DISH_LOCK_KEY_PREFIX + key);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (locked) {
                // 双重检查：拿到锁后再查一次缓存，可能已被其他线程回填
                json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    return JSONUtil.toList(json, DishVO.class);
                }
                List<DishVO> result = getDishVOS(dishQuery);
                long ttl = result.isEmpty() ? EMPTY_CACHE_TTL_MINUTES : CACHE_TTL_MINUTES;
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result), ttl, TimeUnit.MINUTES);
                return result;
            }
            // 抢锁超时：降级为直接查库（不加锁），保证查询可用性
            log.warn("菜品查询抢锁超时，降级直接查库, key={}", key);
            return getDishVOS(dishQuery);
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
    private String generateCacheKey(DishQuery query) {
        StringBuilder sb = new StringBuilder(DISH_CACHE_KEY_PREFIX);

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

        return sb.toString();
    }

}
