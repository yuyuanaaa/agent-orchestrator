package com.kanodays88.agentplatform.agent.simpleChat;

import com.kanodays88.agentplatform.advisor.MyLoggerAdvisor;
import com.kanodays88.agentplatform.common.ChatSystem;
import com.kanodays88.agentplatform.content.BaseContent;
import com.kanodays88.agentplatform.memory.RedisChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.util.List;

public class SimpleChatAgent {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolCallback[] allTools;

    public SimpleChatAgent(OpenAiChatModel chatModel,ToolCallback[] allTools, StringRedisTemplate stringRedisTemplate) throws IOException {
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
