package com.agentorchestrator.platform.agent.rag;

import com.agentorchestrator.platform.advisor.MyLoggerAdvisor;
import com.agentorchestrator.platform.agent.ToolCallAgent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

public class RAGAgent extends ToolCallAgent {

    public RAGAgent(ToolCallback[] toolCallbacks, ChatModel openAiChatModel) {
        super(toolCallbacks);
        //设置智能体名字
        this.setName("RAGAgent");
        //设置最大执行步数
        this.setMaxSteps(5);
        //设置会话客户端
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
