package com.agentorchestrator.platform.agent.simpleChat;

import com.agentorchestrator.platform.advisor.MyLoggerAdvisor;
import com.agentorchestrator.platform.common.ChatSystem;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.memory.RedisChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.util.List;

public class SimpleChatAgent {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolCallback[] allTools;

    public SimpleChatAgent(ChatModel chatModel, ToolCallback[] allTools, StringRedisTemplate stringRedisTemplate) throws IOException {
        this.chatClient = ChatClient.builder(chatModel).defaultAdvisors(new MyLoggerAdvisor()).build();
        this.chatMemory = new RedisChatMemory(stringRedisTemplate, BaseContent.getUser().getUserName());
        this.allTools = allTools;
    }

    public String simpleChat(String userPrompt,String chatId){
        //获取历史会话
        List<Message> messages = chatMemory.get(chatId);
        Prompt historyChat = new Prompt(messages);
        return chatClient.prompt(historyChat).system(ChatSystem.CHAT_SYSTEM).user(userPrompt).toolCallbacks(allTools).call().content();
    }

}
