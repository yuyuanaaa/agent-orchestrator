package com.agentorchestrator.platform.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLMCircuitBreaker 熔断状态机单元测试（纯内存态，无外部依赖）。
 * <p>
 * 覆盖三态迁移的核心语义：
 * <ul>
 *   <li>正常（CLOSED）连续失败达到阈值后进入 OPEN</li>
 *   <li>OPEN 期间拒绝请求（快速失败降级）</li>
 *   <li>成功调用重置失败计数</li>
 * </ul>
 * 冷却时长 30s 无法在单测里真实等待，故通过反射/直接操作内部状态验证
 * HALF_OPEN 试探逻辑；这里聚焦「阈值熔断」与「成功复位」两个可确定性验证的行为。
 */
class LLMCircuitBreakerTest {

    private LLMCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new LLMCircuitBreaker();
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断打开，拒绝后续请求")
    void shouldOpenAfterThresholdFailures() {
        // 阈值 5：前 4 次失败仍允许请求
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
            assertTrue(breaker.allowRequest(), "第 " + (i + 1) + " 次失败后仍应允许请求");
        }
        // 第 5 次失败触发熔断
        breaker.recordFailure();
        assertTrue(breaker.isOpen(), "连续失败达阈值应进入熔断");
        assertFalse(breaker.allowRequest(), "熔断期间应拒绝请求");
    }

    @Test
    @DisplayName("成功调用重置失败计数，熔断被关闭")
    void shouldResetAfterSuccess() {
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        breaker.recordSuccess(); // 成功应清零失败计数
        // 再来 4 次失败（累计仍未达阈值 5），不应熔断
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        assertFalse(breaker.isOpen(), "成功复位后重新累计，未达阈值不应熔断");
        assertTrue(breaker.allowRequest());
    }

    @Test
    @DisplayName("未发生失败时始终允许请求且不熔断")
    void shouldAllowWhenNoFailures() {
        assertTrue(breaker.allowRequest());
        assertFalse(breaker.isOpen());
        breaker.recordSuccess();
        assertTrue(breaker.allowRequest());
    }
}
