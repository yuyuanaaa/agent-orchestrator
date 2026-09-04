package com.kanodays88.agentplatform.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 菜单（菜品 / 套餐）查询缓存的统一管理入口。
 * <p>
 * 存在的意义：缓存 key 前缀此前散落在 {@code DishTool} 与 {@code SetmealTool} 各自的类里，
 * 管理端改数据时没有任何一处能感知到"有哪些 key 需要失效"，导致
 * 「只做了 Cache-Aside 的读路径、没有写路径的失效」，最长 30 分钟的数据不一致。
 * <p>
 * 现在前缀常量收敛到本类，写路径（管理端增删改）统一调用这里的失效方法，
 * 读路径（DishTool / SetmealTool）复用同一批前缀常量，保证单一真相源。
 * <p>
 * 失效策略说明（面试可展开）：
 * <ul>
 *   <li>采用 <b>Cache-Aside + 写失效</b>：更新数据库后<b>删除缓存</b>而不是更新缓存。
 *       更新缓存存在并发写导致的「旧值覆盖新值」问题；删除则是幂等的，
 *       下一个读请求自然会回源重建出最新值。</li>
 *   <li>删除走 <b>SCAN 游标迭代</b>，不用 KEYS。KEYS 会一次性遍历整个 keyspace
 *       并阻塞 Redis 单线程，生产环境属于高危命令；SCAN 分批返回，对主线程影响可控。</li>
 *   <li>批量 delete 而非逐条 delete，减少网络往返。</li>
 * </ul>
 */
@Service
@Slf4j
public class MenuCacheService {

    /** 菜品查询缓存 key 前缀 */
    public static final String DISH_CACHE_KEY_PREFIX = "dish:query:";
    /** 套餐查询缓存 key 前缀 */
    public static final String SETMEAL_CACHE_KEY_PREFIX = "setmeal:query:";

    /** SCAN 单次迭代建议返回的元素数量（非精确值，用于权衡单次耗时与迭代轮数） */
    private static final long SCAN_COUNT = 500;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 失效菜品查询缓存。
     * 菜品变更会同时影响套餐查询（套餐支持按所含菜品名过滤），故一并失效套餐缓存。
     */
    public void evictDishCache() {
        evictByPrefix(DISH_CACHE_KEY_PREFIX);
        evictByPrefix(SETMEAL_CACHE_KEY_PREFIX);
    }

    /** 失效套餐查询缓存。套餐变更不影响菜品查询（菜品查询结果不含套餐信息）。 */
    public void evictSetmealCache() {
        evictByPrefix(SETMEAL_CACHE_KEY_PREFIX);
    }

    /**
     * 失效全部菜单缓存。
     * 用于分类变更——分类名直接参与缓存 key 的拼接（{@code c:分类名}），
     * 分类改名或删除后旧 key 语义已失效，需要整体清理。
     */
    public void evictAllMenuCache() {
        evictByPrefix(DISH_CACHE_KEY_PREFIX);
        evictByPrefix(SETMEAL_CACHE_KEY_PREFIX);
    }

    /**
     * 按前缀批量删除缓存 key。
     * <p>
     * 失效失败不抛异常、不影响主流程：缓存是加速手段而非数据源，
     * 删除失败最坏结果是读到 TTL 到期前的旧数据，不应让管理端的写操作因此失败。
     */
    private void evictByPrefix(String prefix) {
        String pattern = prefix + "*";
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.error("菜单缓存失效失败（不影响主流程，等待 TTL 自然过期）, prefix={}", prefix, e);
            return;
        }

        if (keys.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.delete(keys);
            log.info("菜单缓存已失效, prefix={}, 删除 key 数量={}", prefix, keys.size());
        } catch (Exception e) {
            log.error("菜单缓存批量删除失败, prefix={}, keyCount={}", prefix, keys.size(), e);
        }
    }
}
