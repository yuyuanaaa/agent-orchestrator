package com.agentorchestrator.platform.config.mock;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Mock 对话模型：在 {@code mock} profile 下替代真实的 OpenAI/DashScope 模型，
 * 让面试官 clone 仓库后无需任何 API Key 即可跑通「路由 → 规划 → 执行 → 蒸馏 → 汇总」
 * 完整链路（真实调用会被这里兜底拦截）。
 * <p>
 * 实现说明：{@link ChatModel} 接口只有一个抽象方法 {@link #call(Prompt)}，其余
 * （{@code call(String)}、{@code stream}、{@code getDefaultOptions} 等）都是 default。
 * 本实现只覆盖 {@code call(Prompt)}，SSE 流式路径由上游 {@code ChatClient} 对
 * {@code call} 结果做一次性发送，因此无需实现 {@code stream}。
 * <p>
 * 返回内容是一个固定的说明性文本，明确标注「当前为 Mock 模式」，避免用户误以为
 * 拿到的是真实模型输出。生产环境（非 mock profile）不会加载本类。
 */
public class MockChatModel implements ChatModel {

    /** Mock 返回文案：让链路跑得通，同时自我标识为 mock，防止误解 */
    private static final String MOCK_REPLY =
            "[Mock 模式] 当前运行在免 Key 演示模式，未调用真实 LLM。"
                    + "本条为占位回复：完整的「路由 → 规划 → 执行 → 蒸馏 → 汇总」链路已在此模式下贯通，"
                    + "接入真实 API Key 后即可获得真实智能体输出。";

    @Override
    public ChatResponse call(Prompt prompt) {
        // 对任意 prompt 返回固定占位回复，保证下游 content() 不为空、链路不断
        AssistantMessage message = new AssistantMessage(MOCK_REPLY);
        Generation generation = new Generation(message);
        return new ChatResponse(List.of(generation));
    }
}
