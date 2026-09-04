package com.agentorchestrator.platform.common;

import com.agentorchestrator.platform.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 对话限流器：基于 Redis 固定窗口计数，对单用户做 QPS 级别的并发保护。
 * <p>
 * 实现思路（克制，不引入 Sentinel/Resilience4j 重依赖）：
 * <ul>
 *   <li>key = {@code chat:ratelimit:{userName}:{窗口起始秒}}，窗口起点取当前秒向下取整到窗口大小</li>
 *   <li>{@code INCR} 后首次为 1 时用 {@code EXPIRE} 设过期，避免 key 常驻</li>
 *   <li>计数超过阈值即拒绝（抛 {@link BusinessException}），达到限流目的</li>
 * </ul>
 * <p>
 * 为什么用固定窗口而非滑动窗口：本场景只需挡住「单个用户短时间狂刷」这类粗粒度滥用，
 * 固定窗口实现简单、Redis 开销小（每条请求 1 次 INCR + 可能 1 次 EXPIRE），
 * 边界毛刺（窗口交界瞬间放行 2 倍）对对话场景可接受。
 */
@Slf4j
@Component
public class ChatRateLimiter {

    /** 单用户每窗口允许的最大请求数 */
    private static final int MAX_REQUESTS_PER_WINDOW = 30;

    /** 窗口大小（秒） */
    private static final long WINDOW_SECONDS = 60;

    private static final String KEY_PREFIX = "chat:ratelimit:";

    private final StringRedisTemplate stringRedisTemplate;

    public ChatRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取一次请求配额。
     *
     * @param userName 当前登录用户名
     * @throws BusinessException 超过限流阈值时抛出，提示用户稍后重试
     */
    public void checkRateLimit(String userName) {
        long windowStart = System.currentTimeMillis() / 1000 / WINDOW_SECONDS * WINDOW_SECONDS;
        String key = KEY_PREFIX + userName + ":" + windowStart;

        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            // Redis 异常时放行（限流降级为不拦截，保证可用性优先），由调用方日志兜底
            log.warn("限流计数异常，放行请求 userName={}", userName);
            return;
        }
        // 首次计数时设置过期，窗口结束后 key 自动回收
        if (count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS * 2));
        }
        if (count > MAX_REQUESTS_PER_WINDOW) {
            log.warn("触发限流 userName={}, count={}, window={}", userName, count, windowStart);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "请求过于频繁，请稍后重试");
        }
    }
}
