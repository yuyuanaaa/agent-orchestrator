package com.agentorchestrator.platform.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用熔断器：轻量状态机（CLOSED → OPEN → HALF_OPEN），不引入 Resilience4j 等重依赖。
 * <p>
 * 触发条件与恢复策略：
 * <ul>
 *   <li><b>CLOSED</b>（正常）：连续失败 {@value #FAILURE_THRESHOLD} 次后切到 OPEN</li>
 *   <li><b>OPEN</b>（熔断）：直接快速失败，不再调用下游 LLM，持续 {@value #COOLDOWN_MILLIS} 毫秒</li>
 *   <li><b>HALF_OPEN</b>（试探）：冷却结束后放行一次请求，成功则回到 CLOSED，失败则重新 OPEN</li>
 * </ul>
 * <p>
 * 作用：LLM（DashScope/OpenAI 兼容接口）偶发超时或限频时，避免请求雪崩式打到下游，
 * 熔断期间由上层（ChatController）返回降级文案「AI 服务暂时不可用」。
 * 单机内存态即可满足本项目单实例部署；多实例需换 Redis 计数（此处不展开）。
 */
@Slf4j
@Component
public class LLMCircuitBreaker {

    /** 连续失败多少次后熔断 */
    private static final int FAILURE_THRESHOLD = 5;

    /** 熔断冷却时长（毫秒），冷却结束后进入 HALF_OPEN */
    private static final long COOLDOWN_MILLIS = 30_000L;

    /** 连续失败计数（CLOSED 状态下累计） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 熔断打开时刻（epoch millis），用于计算冷却是否结束 */
    private final AtomicLong openTimestamp = new AtomicLong(0);

    /** 是否处于 HALF_OPEN（放行一次试探）状态 */
    private final AtomicInteger halfOpenProbe = new AtomicInteger(0);

    /** 判断是否允许发起 LLM 调用；熔断中返回 false */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long openAt = openTimestamp.get();

        if (openAt > 0) {
            // OPEN 状态：冷却未结束则拒绝
            if (now - openAt < COOLDOWN_MILLIS) {
                return false;
            }
            // 冷却结束：抢 HALF_OPEN 试探名额，只有一个请求能进入试探
            if (halfOpenProbe.compareAndSet(0, 1)) {
                return true;
            }
            // 已有其他请求在试探，本次仍拒绝
            return false;
        }
        return true;
    }

    /** 记录一次成功：重置失败计数，若处于 HALF_OPEN 则关闭熔断 */
    public void recordSuccess() {
        failureCount.set(0);
        openTimestamp.set(0);
        halfOpenProbe.set(0);
    }

    /** 记录一次失败：累计失败数，达到阈值则打开熔断 */
    public void recordFailure() {
        // HALF_OPEN 试探失败 → 立即重新打开熔断
        if (halfOpenProbe.compareAndSet(1, 0)) {
            openTimestamp.set(System.currentTimeMillis());
            failureCount.set(0);
            log.warn("[CircuitBreaker] HALF_OPEN 试探失败，重新熔断");
            return;
        }
        int failures = failureCount.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            openTimestamp.set(System.currentTimeMillis());
            failureCount.set(0);
            log.warn("[CircuitBreaker] 连续 {} 次失败，熔断打开，冷却 {}ms", failures, COOLDOWN_MILLIS);
        }
    }

    /** 当前是否处于熔断（OPEN 或 HALF_OPEN 试探中），供降级文案判断 */
    public boolean isOpen() {
        long openAt = openTimestamp.get();
        return openAt > 0 && (System.currentTimeMillis() - openAt < COOLDOWN_MILLIS);
    }
}
