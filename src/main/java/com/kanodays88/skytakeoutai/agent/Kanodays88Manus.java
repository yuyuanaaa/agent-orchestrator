package com.kanodays88.skytakeoutai.agent;

import com.kanodays88.skytakeoutai.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

public class Kanodays88Manus extends ToolCallAgent{

    public Kanodays88Manus(ToolCallback[] allTools, ChatModel openAiChatModel){
        //父类构造函数初始化工具
        super(allTools);
        //设置智能体名字
        this.setName("kanodays88Manus");
        //设置最大执行步数
        this.setMaxSteps(5);
        //设置会话客户端
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
