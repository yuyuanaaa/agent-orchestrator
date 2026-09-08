package com.agentorchestrator.platform.agent.router;

import com.agentorchestrator.platform.advisor.MyLoggerAdvisor;
import com.agentorchestrator.platform.agent.sse.SSESend;
import com.agentorchestrator.platform.common.ChatSystem;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.memory.RedisChatMemory;
import com.agentorchestrator.platform.skill.Skill;
import com.agentorchestrator.platform.skill.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RouterAgent {

    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9][0-9xX]{4,9}");
    private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "(?:收货地址|配送地址|送到|送至|配送至|地址)\\s*[是为:：]?\\s*([\\p{L}\\p{N}·]{2,})");

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final ToolCallback[] allTools;
    private final SkillRegistry skillRegistry;
    private final SseEmitter sseEmitter;

    public RouterAgent(ChatModel model, VectorStore vectorStore, ToolCallback[] allTools, SkillRegistry skillRegistry, SseEmitter sseEmitter, StringRedisTemplate stringRedisTemplate) throws IOException {
        this.chatClient = ChatClient.builder(model).defaultAdvisors(new MyLoggerAdvisor()).build();
        this.vectorStore = vectorStore;
        this.chatMemory = new RedisChatMemory(stringRedisTemplate, BaseContent.getUser().getUserName());
        this.allTools = allTools;
        this.skillRegistry = skillRegistry;
        this.sseEmitter = sseEmitter;
    }


    public RouteDecisionTotal route(String userPrompt, String conversationId) {
        SSESend.sendEventThink(sseEmitter,"正在进行意图分析...\n");
        //用LLM匹配这次用户提问所使用的skill
        List<String> selectedSkillNames = selectSkillsWithLLM(userPrompt);
        List<Skill> skills = buildSelectedSkillsContext(selectedSkillNames);
        String skillContext = skills == null||skills.isEmpty()?"未匹配到技能":skillRegistry.getSkillsPromptContext(skills);
//        //计算RAG的相似度
//        java.util.List<Document> documents = getVectorRelevanceScore(userPrompt);
//        double vectorScore = documents == null||documents.isEmpty()?0.0:documents.get(0).getScore();
        RouteDecision decision = llmClassify(userPrompt, conversationId, skillContext, skills);
        SSESend.sendEventThink(sseEmitter,decision.reason()+"\n");
        //将LLM匹配到的全部skill名称携带到RouteDecision中，供下游Agent使用
        return new RouteDecisionTotal(
                decision,
                skills
        );
    }

    private List<Document> getVectorRelevanceScore(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        SearchRequest request = SearchRequest.builder()
                .query(prompt)
                .topK(1)
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null || documents.isEmpty()) return null;
        return documents;
    }

    private RouteDecision llmClassify(String prompt, String conversationId, String skillContext, List<Skill> matchedSkills) {
        String history = buildHistoryContext(conversationId);

        BeanOutputConverter<ClassifyResult> converter = new BeanOutputConverter<>(ClassifyResult.class);
        String systemPrompt = """
                 {role}
                 【当前工作】
                 你是一个问题分类专家，根据用户输入和对话历史，判断问题类型，并结合业务技能生成这次对话的总任务
                 工具信息只用做判断该问题需要用到哪些工具，**绝对禁止实际调用任何工具**
    
                 【匹配到以下业务技能】
                 {skillContext}
    
                 【分类规则（优先级从高到低）】
                 1. 如果匹配到1个或多个业务技能：
                    - 复合意图处理：用户一句话里可能同时包含“查询/浏览”和“下单”两个意图（例如“有什么喝的？帮我点一份”）。
                      只要其中“查询/浏览”部分可以被现有工具独立执行，就必须判定为 COMPLEX_TASK，先执行查询展示结果，再视情况进入下单流程。
                    - 若存在至少1个技能信息充分、可以执行 → COMPLEX_TASK
                    - 只有当所有匹配技能都缺少启动必需的“重要程度：高”参数，且该技能完全无法启动时 → AMBIGUOUS
                      （missingInfo 只列出当前确实缺失、导致无法推进的信息点；禁止把“浏览菜单”类需求也当作缺失项）
                    - 多个技能匹配时，优先选择与用户输入语义最相关、信息最充分的技能作为主任务依据
                 2. 如果没有匹配到任何业务技能，但解决该问题需要使用2种及以上工具 → COMPLEX_TASK
                 3. 如果没有匹配到任何业务技能，且结合历史对话和用户问题（无历史会话就单独判断用户问题）判断该问题模糊不清、语义不明确 → AMBIGUOUS
                 4. 如果只需用到一个工具，或者能够从历史对话中找到答案 → SIMPLE_CHAT
    
                 【输出参数说明】
                 questionType: 问题分类的结果，只能是COMPLEX_TASK / AMBIGUOUS / SIMPLE_CHAT三者之一
                 reason: 详细的判断理由，需明确说明匹配到的业务技能（如有）、信息充分性判断依据或问题模糊的具体原因
                 returnQuestion: 反问用户的问题（只有当questionType为AMBIGUOUS才会有内容，否则为空字符串）。
                                反问时不得预假设用户一定要下单，也不得索要配送地址/电话，除非用户已经明确表达了下单意图且仅缺失这些信息。
                 mainTask: 详细总任务：
                    - 如果有业务技能，必须严格按照该技能的业务流程描述撰写
                    - 如果未匹配到业务技能但为COMPLEX_TASK，需明确说明核心任务和需要用到的工具
                    - 如果为SIMPLE_CHAT或AMBIGUOUS，需简要说明当前对话状态
                    - 如果用户询问的问题与匹配的业务无关，或者没有匹配到业务，需要用到联网搜索工具
    
                 【输出要求】
                 严格按照以下JSON格式输出，不得包含任何额外的解释、说明或markdown内容：
                 JSON格式里的内容不得使用双引号：
                    错误案例："Content":"用户的名字叫"五条五""
                    正确案例："Content":"用户的名字叫五条五"
                 具体json格式：{format}
                """;

        String userContent = "用户问题：" + prompt + "\n历史对话：\n" + history;

        ChatOptions chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false) // 核心配置：禁用Spring AI内部自动工具执行
                // 可保留原有的其他通义千问专属配置（模型名、温度、top_p等）
                .build();

        Prompt userPrompt = new Prompt(new UserMessage(userContent), chatOptions);

        ClassifyResult result = chatClient.prompt(userPrompt)
                .system(s -> s.text(systemPrompt)
                        .param("skillContext", skillContext)
                        .param("format", converter.getJsonSchema())
                        .param("role", ChatSystem.CHAT_SYSTEM))
                .toolCallbacks(allTools)
                .call()
                .entity(converter);

        if (result == null) {
            return new RouteDecision(QuestionType.SIMPLE_CHAT, "LLM分类失败，默认简单对话",null,userContent);
        }

        QuestionType type;
        try {
            type = QuestionType.valueOf(result.questionType());
        } catch (IllegalArgumentException e) {
            type = QuestionType.SIMPLE_CHAT;
        }

        // 确定性兜底：只要任一匹配技能的高重要度参数已满足，就不应被判定为 AMBIGUOUS
        if (type == QuestionType.AMBIGUOUS && matchedSkills != null && !matchedSkills.isEmpty()) {
            String combinedInput = prompt + " " + history;
            for (Skill skill : matchedSkills) {
                if (skillHasCompleteHighParams(skill, combinedInput)) {
                    type = QuestionType.COMPLEX_TASK;
                    break;
                }
            }
        }

        return new RouteDecision(type, result.reason(), result.returnQuestion(),result.mainTask());
    }

    /**
     * 判断某个技能的高重要度参数是否都已给出可用的值。
     * 参数名是英文标识，直接做子串匹配会漏掉中文表达，因此按参数语义
     * 检查“地址标记后有地址内容 / 电话号码 / 订单号”等常见输入形态。
     */
    private boolean skillHasCompleteHighParams(Skill skill, String combinedInput) {
        List<String> highParams = skill.getHighImportanceParamNames();
        if (highParams == null || highParams.isEmpty()) return false;
        for (String param : highParams) {
            if (!paramValuePresent(param, combinedInput)) {
                return false;
            }
        }
        return true;
    }

    private boolean paramValuePresent(String param, String combinedInput) {
        switch (param.toLowerCase()) {
            case "phone":
                return PHONE_PATTERN.matcher(combinedInput).find();
            case "address":
                Matcher addressMatcher = ADDRESS_PATTERN.matcher(combinedInput);
                return addressMatcher.find() && addressMatcher.group(1).trim().length() >= 2;
            case "orderid":
            case "order_id":
            case "ordernumber":
            case "order_number":
                return ORDER_NUMBER_PATTERN.matcher(combinedInput).find()
                        || combinedInput.contains("订单号")
                        || combinedInput.contains("订单编号");
            default:
                // 保留英文参数名匹配，覆盖其余高重要性参数
                return combinedInput.toLowerCase().contains(param.toLowerCase());
        }
    }

    private record ClassifyResult(String questionType, String reason, String returnQuestion,String mainTask) {}
    private record SkillSelection(List<String> skillNames) {}

    private List<String> selectSkillsWithLLM(String userPrompt) {
        String skillsSummary = skillRegistry.getAllSkills().stream()
                .map(s -> "- " + s.getName() + ": " + s.getDescription())
                .collect(Collectors.joining("\n"));
        if (skillsSummary.isBlank()) return List.of();
        //获取历史会话
        String history = buildHistoryContext(BaseContent.getChatId());

        BeanOutputConverter<SkillSelection> converter = new BeanOutputConverter<>(SkillSelection.class);
        String prompt = """
                根据用户输入和历史对话，从以下可用技能中选择最匹配的业务技能（可多选，也可不选）：

                {skills}

                选择原则：
                1. 仅基于技能名称和描述进行语义匹配。
                2. 用户问“有什么吃的/喝的/推荐的”等浏览类问题时，优先选择查询类技能（dish_and_setmeal_query），不要选择下单类技能。
                3. 用户已经明确说出具体菜品/套餐并要求下单时，才选择下单类技能（place_order）。
                4. 一句话里同时包含浏览和下单意图时，可以同时选择查询类技能和下单类技能。
                【历史会话】：{history}

                json输出格式：{format}
                """;

        SkillSelection result = chatClient.prompt()
                .system(s -> s.text(prompt)
                        .param("skills", skillsSummary)
                        .param("format", converter.getJsonSchema())
                        .param("history",history))
                .user(userPrompt)
                .call()
                .entity(converter);

        return result != null ? result.skillNames() : List.of();
    }

    /**
     * 通过skillName加载详细的skill
     * @param skillNames
     * @return
     */
    private List<Skill> buildSelectedSkillsContext(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return null;
        List<Skill> selected = skillNames.stream()
                .map(name -> skillRegistry.getSkill(name))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (selected.isEmpty()) return null;
        //将指定的skill集合，格式化为LLM的上下文文本格式
        return selected;
    }

    private String buildHistoryContext(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) return "无历史对话";
        return history.stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .collect(Collectors.joining("\n"));
    }
}
