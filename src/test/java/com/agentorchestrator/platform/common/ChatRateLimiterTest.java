package com.agentorchestrator.platform.common;

import com.agentorchestrator.platform.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ChatRateLimiter 限流器单元测试（mock Redis，验证计数与阈值判定逻辑）。
 */
class ChatRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ChatRateLimiter rateLimiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rateLimiter = new ChatRateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("计数未超阈值时放行")
    void shouldAllowWhenUnderLimit() {
        when(valueOps.increment(anyString())).thenReturn(5L);
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("alice"));
    }

    @Test
    @DisplayName("计数超过阈值时抛异常拒绝")
    void shouldRejectWhenOverLimit() {
        when(valueOps.increment(anyString())).thenReturn(31L);
        assertThrows(BusinessException.class, () -> rateLimiter.checkRateLimit("alice"));
    }

    @Test
    @DisplayName("Redis 返回 null（异常）时降级放行，不拦截")
    void shouldAllowWhenRedisFails() {
        when(valueOps.increment(anyString())).thenReturn(null);
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("alice"));
    }

    @Test
    @DisplayName("首次计数时设置过期时间")
    void shouldSetExpireOnFirstCount() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        rateLimiter.checkRateLimit("alice");
        Mockito.verify(redisTemplate).expire(anyString(), any(Duration.class));
    }
}
