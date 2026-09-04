package com.kanodays88.agentplatform.agent;

import com.kanodays88.agentplatform.agent.router.RouterAgent;
import com.kanodays88.agentplatform.agent.simpleChat.SimpleChatAgent;
import com.kanodays88.agentplatform.skill.SkillRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 智能体创建工厂 —— 统一收敛所有 Agent 的实例化入口。
 * <p>
 * Agent 实例是「每请求/每子任务」粒度的有状态对象（携带各自的会话记忆与 SSE 连接），
 * 不适合做成单例 Bean；但它们的依赖（模型、向量库、技能注册表、工具集）是单例。
 * 此前 {@code new RouterAgent(...)} 散落在 Controller，依赖清单变化时要改多处；
 * 现在由工厂注入依赖，调用方只传运行时参数。
 * <p>
 * 后续若引入 Agent 池化或统一生命周期埋点（Token 统计、轨迹落库），只需改这一个类。
 */
@Component
public class AgentFactory {

    private final OpenAiChatModel chatModel;
    private final VectorStore vectorStore;
    private final ToolCallback[] allTools;
    private final SkillRegistry skillRegistry;
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public AgentFactory(OpenAiChatModel chatModel,
                        VectorStore vectorStore,
                        ToolCallback[] allTools,
                        SkillRegistry skillRegistry,
                        StringRedisTemplate stringRedisTemplate) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.allTools = allTools;
        this.skillRegistry = skillRegistry;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 创建意图路由智能体（每个 SSE 会话一个实例） */
    public RouterAgent createRouterAgent(SseEmitter emitter) throws IOException {
        return new RouterAgent(chatModel, vectorStore, allTools, skillRegistry, emitter, stringRedisTemplate);
    }

    /** 创建简单对话智能体（每个 SSE 会话一个实例） */
    public SimpleChatAgent createSimpleChatAgent() throws IOException {
        return new SimpleChatAgent(chatModel, allTools, stringRedisTemplate);
    }

    /** 创建任务执行智能体，按子任务契约只挂载所需工具 */
    public PlanAgent createManus(ToolCallback[] tools) {
        return new PlanAgent(tools, chatModel);
    }
}
