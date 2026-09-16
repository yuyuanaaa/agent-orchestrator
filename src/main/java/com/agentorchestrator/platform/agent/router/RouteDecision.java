package com.agentorchestrator.platform.agent.router;

/**
 * 路由决策结果 —— 记录 RouterAgent 对用户输入的分类判定。
 * <p>
 * 只承载「这一次该怎么处理」的结论：问题类型（SIMPLE_CHAT / COMPLEX_TASK / AMBIGUOUS）、
 * 判定理由、需要反问用户的问题，以及总结出的本次对话总任务。
 * <p>
 * <b>命中的业务技能不在这里</b>，而在 {@link RouteDecisionTotal#skills()}。
 * 两者在 {@code ChatController} 中被一起消费：{@code decision} 决定走哪条分支，
 * {@code skills} 传给 {@code PlanExecute} 注入任务分解提示词。
 */
public record RouteDecision(
        QuestionType questionType,      // 问题类型
        String reason,                  // 判定理由
        String returnQuestion,          // 要反问用户的问题，只当question为AMIGUOUS时有值
        String mainTask                 // 总结出这次对话的总任务
) {}
