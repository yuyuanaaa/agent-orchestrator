package com.kanodays88.agentplatform.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基准测试：Redis 批量失效 —— SCAN 游标 vs KEYS 命令的耗时对比。
 * <p>
 * 对比两种批量删除 N 个匹配 key 的策略：
 * <ul>
 *   <li>【当前实现】MenuCacheService 用 SCAN 游标迭代（O(N) 但分批、非阻塞）</li>
 *   <li>【反例 baseline】KEYS pattern 一次性返回所有 key 再 DELETE（O(N) 且阻塞 Redis 主线程）</li>
 * </ul>
 * KEYS 是生产高危命令：会阻塞 Redis 单线程处理其他请求。SCAN 是官方推荐替代。
 * <p>
 * 前置：本地 Redis 监听 6379（端口已确认可达）。如未启动，测试会自动跳过。
 * 运行：mvnw test -Dtest=CacheScanBenchmark
 */
@SpringBootTest(classes = com.kanodays88.agentplatform.AgentPlatformApplication.class)
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        // 用占位符让 Spring AI 客户端 Bean 可创建，但不发起调用
        "spring.ai.openai.chat.api-key=sk-benchmark-placeholder",
        "spring.ai.openai.embedding.api-key=sk-benchmark-placeholder"
})
class CacheScanBenchmark {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private static final String TEST_PREFIX = "bench:menu:";
    private static final int KEY_COUNT = 5000;
    private static final int WARMUP_ITERATIONS = 1;

    @Test
    @DisplayName("Redis 批量失效：SCAN 游标 vs KEYS 命令耗时对比")
    void measureScanVsKeys() {
        // 先探测 Redis 是否可达
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            String pong = conn.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                System.out.println("[SKIP] Redis 未响应 PING，跳过基准");
                return;
            }
        } catch (Exception e) {
            System.out.println("[SKIP] Redis 不可达 (" + e.getMessage() + ")，跳过基准");
            return;
        }

        // 准备：写入 KEY_COUNT 个测试 key
        long prepStart = System.nanoTime();
        populateKeys();
        long prepMs = (System.nanoTime() - prepStart) / 1_000_000;
        System.out.printf("准备：写入 %,d 个 key（耗时 %,d ms）%n", KEY_COUNT, prepMs);
        System.out.println();

        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            populateKeys();
            measureKeys();
            populateKeys();
            measureScan();
        }

        // 多次测量取平均
        int runs = 3;
        long scanTotal = 0, keysTotal = 0;
        for (int i = 0; i < runs; i++) {
            populateKeys();
            scanTotal += measureScan();
            populateKeys();
            keysTotal += measureKeys();
        }
        long scanAvg = scanTotal / runs;
        long keysAvg = keysTotal / runs;

        double ratio = (double) keysAvg / scanAvg;

        System.out.println("===== Redis SCAN vs KEYS 基准 =====");
        System.out.printf("测试 key 数量：%,d | 运行次数：%d（取平均）%n", KEY_COUNT, runs);
        System.out.printf("SCAN 游标批量失效 平均耗时：%,d ms%n", scanAvg);
        System.out.printf("KEYS 命令批量失效 平均耗时：%,d ms%n", keysAvg);
        System.out.printf("SCAN / KEYS 耗时比：%.2f（KEYS 在阻塞 Redis 单线程方面代价更大）%n", ratio);
        System.out.println();
        System.out.println("注：KEYS 是 O(N) 单次返回所有匹配 key，阻塞 Redis 主线程；");
        System.out.println("    SCAN 是 O(N) 但分批游标迭代，非阻塞、生产环境推荐。");
        System.out.println("===== END =====");

        // 清理测试数据
        cleanupKeys();
    }

    private void populateKeys() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            for (int i = 0; i < KEY_COUNT; i++) {
                byte[] key = (TEST_PREFIX + i).getBytes(StandardCharsets.UTF_8);
                byte[] val = ("v" + i).getBytes(StandardCharsets.UTF_8);
                conn.stringCommands().set(key, val);
            }
        }
    }

    private void cleanupKeys() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            byte[] pattern = (TEST_PREFIX + "*").getBytes(StandardCharsets.UTF_8);
            Set<byte[]> keys = conn.keyCommands().keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                conn.keyCommands().del(keys.toArray(new byte[0][]));
            }
        }
    }

    /** KEYS pattern + DEL（反例 baseline） */
    private long measureKeys() {
        long start = System.nanoTime();
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            byte[] pattern = (TEST_PREFIX + "*").getBytes(StandardCharsets.UTF_8);
            Set<byte[]> keys = conn.keyCommands().keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                conn.keyCommands().del(keys.toArray(new byte[0][]));
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** SCAN 游标 + 批量 DEL（当前实现 MenuCacheService 同样的策略） */
    private long measureScan() {
        long start = System.nanoTime();
        ScanOptions options = ScanOptions.scanOptions().match(TEST_PREFIX + "*").count(500).build();
        List<byte[]> batch = new ArrayList<>(512);
        try (RedisConnection conn = redisConnectionFactory.getConnection();
             Cursor<byte[]> cursor = conn.keyCommands().scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 256) {
                    conn.keyCommands().del(batch.toArray(new byte[0][]));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                conn.keyCommands().del(batch.toArray(new byte[0][]));
                batch.clear();
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
