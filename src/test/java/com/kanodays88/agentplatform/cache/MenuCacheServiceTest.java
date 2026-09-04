package com.kanodays88.agentplatform.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单缓存失效的单元测试（不依赖真实 Redis）。
 * <p>
 * 锁定三条关键契约：
 * <ol>
 *   <li><b>用 SCAN 不用 KEYS</b>——KEYS 会一次性遍历整个 keyspace 并阻塞 Redis 单线程，
 *       属于生产高危命令；一旦有人改回 KEYS，这里的断言会直接失败。</li>
 *   <li><b>菜品变更要连带失效套餐缓存</b>——套餐查询支持按所含菜品名过滤，
 *       菜品改名或下架后套餐查询结果同样会变。</li>
 *   <li><b>失效失败不能拖垮写操作</b>——缓存是加速手段而非数据源，
 *       Redis 抖动最坏结果是读到 TTL 到期前的旧值，不该让管理端的保存失败。</li>
 * </ol>
 */
class MenuCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private MenuCacheService menuCacheService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        menuCacheService = new MenuCacheService();
        ReflectionTestUtils.setField(menuCacheService, "stringRedisTemplate", redisTemplate);
    }

    /**
     * 用内存 Iterator 伪装 Redis 游标，避免依赖真实连接。
     * 直接 mock {@link Cursor} 接口而非手写实现类——Spring Data Redis 各版本的
     * Cursor 契约（getCursorId / getId / getPosition 等）有差异，mock 可以避免被版本变更绊倒。
     */
    private Cursor<String> cursorOf(String... keys) {
        Iterator<String> it = Arrays.asList(keys).iterator();
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(invocation -> it.hasNext());
        when(cursor.next()).thenAnswer(invocation -> it.next());
        return cursor;
    }

    @Test
    @DisplayName("失效必须走 SCAN 游标迭代，禁止使用会阻塞 Redis 的 KEYS 命令")
    void shouldUseScanInsteadOfKeys() {
        // 游标必须先构造完再传入 thenReturn：Mockito 不允许在 thenReturn 的参数求值过程中
        // 再开启一次 stubbing（会触发 UnfinishedStubbing 异常）
        Cursor<String> dishCursor = cursorOf("dish:query:p:NULL-NULL");
        Cursor<String> setmealCursor = cursorOf();
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenReturn(dishCursor, setmealCursor);

        menuCacheService.evictDishCache();

        ArgumentCaptor<ScanOptions> captor = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate, times(2)).scan(captor.capture());
        verify(redisTemplate, never()).keys(anyString());

        List<String> patterns = captor.getAllValues().stream()
                .map(ScanOptions::getPattern)
                .toList();
        assertTrue(patterns.contains("dish:query:*"), "应扫描菜品缓存前缀");
        assertTrue(patterns.contains("setmeal:query:*"), "应扫描套餐缓存前缀");
    }

    @Test
    @DisplayName("菜品变更需同时失效菜品与套餐缓存（套餐支持按所含菜品名查询）")
    void evictDishCacheShouldEvictBothPrefixes() {
        Cursor<String> dishCursor = cursorOf("dish:query:p:NULL-NULL", "dish:query:c:主食p:NULL-NULL");
        Cursor<String> setmealCursor = cursorOf("setmeal:query:p:NULL-NULL");
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenReturn(dishCursor, setmealCursor);

        menuCacheService.evictDishCache();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate, times(2)).delete(keysCaptor.capture());

        List<Collection<String>> deleted = keysCaptor.getAllValues();
        assertEquals(2, deleted.get(0).size(), "菜品缓存应删除 2 个 key");
        assertEquals(1, deleted.get(1).size(), "套餐缓存应删除 1 个 key");
        assertTrue(deleted.get(0).contains("dish:query:p:NULL-NULL"));
        assertTrue(deleted.get(1).contains("setmeal:query:p:NULL-NULL"));
    }

    @Test
    @DisplayName("套餐变更只失效套餐缓存（菜品查询结果不含套餐信息，无需连带清理）")
    void evictSetmealCacheShouldOnlyEvictSetmeals() {
        Cursor<String> setmealCursor = cursorOf("setmeal:query:p:NULL-NULL");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(setmealCursor);

        menuCacheService.evictSetmealCache();

        ArgumentCaptor<ScanOptions> captor = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate, times(1)).scan(captor.capture());
        assertEquals("setmeal:query:*", captor.getValue().getPattern());
    }

    @Test
    @DisplayName("没有匹配 key 时不发起删除，避免无意义的 Redis 往返")
    void shouldSkipDeleteWhenNoKeysMatched() {
        Cursor<String> emptyCursor = cursorOf();
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(emptyCursor);

        menuCacheService.evictSetmealCache();

        verify(redisTemplate, times(1)).scan(any(ScanOptions.class));
        verify(redisTemplate, never()).delete(any(Collection.class));
    }

    @Test
    @DisplayName("Redis 异常时静默降级，不阻断管理端的写操作")
    void shouldNotFailWhenRedisIsDown() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis 连接中断"));

        assertDoesNotThrow(() -> menuCacheService.evictDishCache());
        assertFalse(Thread.currentThread().isInterrupted(), "不应把中断状态留给调用方");
    }
}
