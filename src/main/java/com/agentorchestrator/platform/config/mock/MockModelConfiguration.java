package com.agentorchestrator.platform.config.mock;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Mock 模式配置：仅在 {@code mock} profile 激活时生效。
 * <p>
 * 配合 {@code application-mock.yaml} 使用（其中已通过
 * {@code spring.autoconfigure.exclude} 关闭 OpenAI chat / embedding 的自动装配），
 * 用这里的 Mock 实现顶替真实模型，实现「clone 后免 Key 一键跑通」的面试演示体验。
 * <p>
 * 使用方式：{@code --spring.profiles.active=mock} 或环境变量
 * {@code SPRING_PROFILES_ACTIVE=mock}。生产环境不激活 mock profile，本配置不生效。
 */
@Configuration
@Profile("mock")
public class MockModelConfiguration {

    @Bean
    public ChatModel mockChatModel() {
        return new MockChatModel();
    }

    @Bean
    public EmbeddingModel mockEmbeddingModel() {
        return new MockEmbeddingModel();
    }
}
