package com.agentorchestrator.platform.config.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <b>如何做到「不接 Key 也能跑通链路」</b>：本类按 <b>调用点</b> 而不是按「用户问题」区分回复，
 * 依据是各调用点会把自己的 JSON Schema（{@code BeanOutputConverter#getJsonSchema()}）渲染进提示词，
 * 因此提示词里必然出现对应的属性名：
 * <ol>
 *   <li>含 {@code 当前剩余思考次数} → {@code ToolCallAgent} 的子任务 ReAct 循环（标记取自其系统提示词，
 *       是唯一能可靠区分 ReAct 的特征）。演示模式不调用真实模型，这里通过返回一次
 *       {@code assignmentFinish} 工具调用主动结束子任务——否则 ReAct 会空转到最大步数，
 *       前端会看到同一段文案重复 5 次；</li>
 *   <li>含 {@code skillNames} → {@code RouterAgent} 的技能选择，返回合规的 {@code {"skillNames":[...]}}；</li>
 *   <li>含 {@code questionType} → {@code RouterAgent} 的意图分类，按「是否命中业务技能」给出
 *       {@code COMPLEX_TASK} / {@code SIMPLE_CHAT}，与真实提示词里的分类规则保持一致；</li>
 *   <li>含 {@code subTaskList} → {@code PlanExecute} 的任务拆分，返回两个有依赖关系的子任务，
 *       让「拓扑分层 + 上游结果注入」两个特性在演示中真实生效；</li>
 *   <li>含 {@code 【子任务结果列表】} → {@code PlanExecute} 的结果汇总，回显收到的子任务结果，
 *       让「汇总」这一步的数据流向在免 Key 演示中也可见；</li>
 *   <li>其余（简单对话、RAG 问答、结果蒸馏）→ 返回带 {@code [Mock 模式]} 标识的占位文案。</li>
 * </ol>
 * <b>为什么 ReAct 要放在最前面判断</b>：子任务的「当前任务」文本是上一阶段产出的内容，
 * 若其中夹带了 JSON Schema（见 {@link #FORMAT_TRAILER} 的说明），就会同时命中 {@code subTaskList}；
 * 把结构化输出标记放在前面会让 ReAct 被误判成「任务拆分」，子任务永远收不了尾。
 * <p>
 * 所有回复都显式标注「Mock 模式」，避免使用者误以为拿到的是真实模型输出。
 * 生产环境（非 mock profile）不会加载本类。
 */
@Slf4j
public class MockChatModel implements ChatModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 所有占位输出的统一前缀，便于前端与日志一眼识别 */
    private static final String TAG = "[Mock 模式] ";

    /** 非结构化调用（简单对话 / RAG 问答 / 结果蒸馏）的占位回复 */
    private static final String PLAIN_REPLY =
            TAG + "当前运行在免 Key 演示模式，未调用真实 LLM，本条为占位回复；"
                    + "接入真实 API Key 后即可获得真实的智能体输出。";

    // ==================== 调用点识别标记 ====================

    /** RouterAgent.SkillSelection 的 JSON Schema 属性名 */
    private static final String PROP_SKILL_SELECTION = "skillNames";
    /** RouterAgent.ClassifyResult 的 JSON Schema 属性名 */
    private static final String PROP_INTENT_CLASSIFY = "questionType";
    /** PlanExecute.DecomposedTasks 的 JSON Schema 属性名 */
    private static final String PROP_DECOMPOSE = "subTaskList";
    /** PlanExecute.fuseResults 提示词里的子任务结果小节标题 */
    private static final String MARKER_FUSE = "【子任务结果列表】";
    /** PlanExecute.fuseResults 提示词里紧随其后、仅在有失败任务时出现的失败小节标题 */
    private static final String MARKER_FAILED = "【失败任务】";
    /** 子任务 ReAct 循环的收尾工具名（与 AssignmentFinishTool 的方法名一致） */
    private static final String TOOL_FINISH = "assignmentFinish";
    /**
     * ToolCallAgent 的 ReAct 系统提示词中独有的标记，也是**唯一**能可靠识别 ReAct 调用点的特征。
     * <p>
     * 不用工具名 {@code assignmentFinish} 做标记：汇总提示词会回显子任务的工具执行结果
     * （「…工具 assignmentFinish 完成了它的任务…」），用工具名会把「汇总」误判成「子任务执行」，
     * 最终答复会变成子任务占位文案（该场景已由 MockChatModelTest 覆盖）。
     */
    private static final String MARKER_REACT = "当前剩余思考次数";

    /**
     * Spring AI 结构化输出（{@code .entity(converter)}）追加到提示词末位的「输出格式要求」开头。
     * <p>
     * {@code BeanOutputConverter} 会把一整段格式说明拼在<b>用户消息之后</b>，形如：
     * <pre>
     * Your response should be in JSON format.
     * Do not include any explanations, only provide a RFC8259 compliant JSON response …
     * Here is the JSON Schema instance your output must adhere to:
     * {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"subTaskList":{…
     * </pre>
     * 读「用户输入」时必须先把它剥掉，否则：
     * <ol>
     *   <li>几 KB 的 schema 文本会被当成用户输入，写进子任务的 {@code taskContent}，
     *       下游「上游任务:… 核心结果:…」上下文立刻被污染；</li>
     *   <li>schema 里带有 {@code subTaskList} / {@code skillNames} 等属性名，
     *       会让后续 ReAct 提示词命中「任务拆分」分支——子任务收不了尾、空转到 5 步上限，
     *       开头那段「用户输入」现象就是这么来的（该场景已由 MockChatModelTest 覆盖）。</li>
     * </ol>
     */
    private static final String FORMAT_TRAILER = "Your response should be in JSON format";

    // ==================== 从提示词中读取业务信息的标记 ====================

    /** RouterAgent.llmClassify 的用户消息前缀 */
    private static final String MARKER_USER_QUESTION = "用户问题：";
    /** 分类提示词里紧跟用户问题之后的历史对话小节 */
    private static final String MARKER_HISTORY = "历史对话";
    /** Skill#toPromptContext 的技能标题行前缀 */
    private static final String MARKER_SKILL_HEADING = "## Skill: ";
    /** RouterAgent 未匹配到技能时注入的占位文本 */
    private static final String NO_SKILL_MATCHED = "未匹配到技能";

    /**
     * 技能摘要行：「- skill_name: description」。
     * 允许行首缩进：提示词由模板渲染，代入点可能带上前导空白；
     * 技能名限定 ASCII 标识符，避免误抓描述里的中文列表项。
     */
    private static final Pattern SKILL_LINE_PATTERN =
            Pattern.compile("(?m)^[ \\t]*-[ \\t]*([A-Za-z][A-Za-z0-9_]*)[ \\t]*:");
    /** 技能详细定义标题行：「## Skill: skill_name」，同样允许行首缩进 */
    private static final Pattern SKILL_HEADING_PATTERN =
            Pattern.compile("(?m)^[ \\t]*##[ \\t]*Skill:[ \\t]*([A-Za-z][A-Za-z0-9_]*)[ \\t]*$");

    // ==================== 关键词表（技能名与 resources/skills 下的技能一一对应）====================

    private static final List<String> TRAVEL_KEYWORDS =
            List.of("旅游", "旅行", "行程", "出差", "攻略", "景点", "游玩", "几日游");

    /** 技能关键词表：按技能名 → 触发关键词，顺序即匹配优先级 */
    private static final Map<String, List<String>> SKILL_KEYWORDS = buildSkillKeywords();

    /** 旅游类子任务使用的联网搜索工具 */
    private static final List<String> TRAVEL_TOOLS = List.of("batchWebSearch");
    /** 餐饮类子任务使用的菜品 / 套餐查询工具 */
    private static final List<String> COMMERCE_TOOLS = List.of("queryDish", "querySetmeal");

    private static Map<String, List<String>> buildSkillKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("cancel_order", List.of("取消订单", "退单", "取消这笔", "取消该订单", "不想买了"));
        keywords.put("query_order", List.of("查询订单", "我的订单", "查订单", "订单状态", "订单号", "订单编号"));
        keywords.put("place_order",
                List.of("下单", "帮我点", "点一份", "点个", "来一份", "买一份", "要一份", "加一份"));
        keywords.put("dish_and_setmeal_query",
                List.of("吃", "喝", "菜", "套餐", "菜单", "招牌", "推荐", "口味", "饮品", "主食", "价格", "有什么"));
        keywords.put("general_trip_planner", TRAVEL_KEYWORDS);
        return keywords;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 忽略传入的 options：mock 不做真实推理，只按调用点返回可解析的桩数据
        AssistantMessage message = respond(prompt);
        return new ChatResponse(List.of(new Generation(message)));
    }

    /**
     * 按提示词特征分派回复，见类注释中的调用点说明。
     */
    private AssistantMessage respond(Prompt prompt) {
        if (prompt == null) {
            return text(PLAIN_REPLY);
        }
        // 所有消息文本拼在一起用于识别调用点；用户输入则单独取「最后一条 USER 消息」，
        // 不能依赖 Prompt#getContents() 的拼接顺序（实测部分调用点它只含 system 消息）
        String promptText = prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        if (promptText.isBlank()) {
            return text(PLAIN_REPLY);
        }
        // 演示排障用：mock 的分派完全依赖提示词内容，出问题时能直接在日志里看到收到的原文
        log.debug("[Mock] 收到提示词:\n{}", promptText);
        // ReAct 必须第一个判断：子任务的「当前任务」是上一阶段的产出，可能夹带 JSON Schema
        // （含 subTaskList 等属性名）；先判结构化标记会把 ReAct 误判成任务拆分、空转到最大步数
        if (promptText.contains(MARKER_REACT)) {
            return finishSubTask();
        }
        if (promptText.contains(PROP_SKILL_SELECTION)) {
            return text(selectSkills(prompt, promptText));
        }
        if (promptText.contains(PROP_INTENT_CLASSIFY)) {
            return text(classifyIntent(prompt, promptText));
        }
        if (promptText.contains(PROP_DECOMPOSE)) {
            return text(decomposeTasks(prompt));
        }
        if (promptText.contains(MARKER_FUSE)) {
            return text(fuseResults(promptText));
        }
        return text(PLAIN_REPLY);
    }

    /**
     * 技能选择：从提示词列出的可选技能中，按用户输入的关键词做确定性匹配。
     * 只返回提示词里真实存在的技能名，避免编造不存在的技能。
     */
    private String selectSkills(Prompt prompt, String promptText) {
        String question = userInput(prompt);
        Set<String> available = new LinkedHashSet<>();
        Matcher matcher = SKILL_LINE_PATTERN.matcher(promptText);
        while (matcher.find()) {
            available.add(matcher.group(1));
        }
        return toJson(Map.of(PROP_SKILL_SELECTION, matchSkills(question, available)));
    }

    /**
     * 意图分类：命中业务技能 → COMPLEX_TASK（走规划执行），未命中 → SIMPLE_CHAT（简单对话），
     * 与真实提示词里的分类规则一致；演示模式不返回 AMBIGUOUS，避免演示时被反问问断。
     */
    private String classifyIntent(Prompt prompt, String promptText) {
        String question = userInput(prompt);
        List<String> matched = new ArrayList<>();
        Matcher matcher = SKILL_HEADING_PATTERN.matcher(promptText);
        while (matcher.find()) {
            matched.add(matcher.group(1));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(PROP_INTENT_CLASSIFY, "SIMPLE_CHAT");
        body.put("reason", TAG + "未匹配到业务技能，按分类规则判定为简单对话");
        body.put("returnQuestion", "");
        body.put("mainTask", "直接回答用户问题：" + question);

        if (!matched.isEmpty() && !promptText.contains(NO_SKILL_MATCHED)) {
            String skills = String.join("、", matched);
            body.put(PROP_INTENT_CLASSIFY, "COMPLEX_TASK");
            body.put("reason", TAG + "命中业务技能 " + skills + "，按分类规则判定为复杂任务");
            body.put("mainTask", "按业务技能「" + skills + "」的流程处理用户请求：" + question);
        }
        return toJson(body);
    }

    /**
     * 任务拆分：返回两个有依赖关系的子任务（子任务2 依赖子任务1 的查询结果），
     * 让「拓扑分层」与「上游结果注入下游上下文」在演示中真实生效。
     */
    private String decomposeTasks(Prompt prompt) {
        String task = userInput(prompt);
        List<String> queryTools =
                containsAny(task, TRAVEL_KEYWORDS) ? TRAVEL_TOOLS : COMMERCE_TOOLS;

        List<Map<String, Object>> subTasks = new ArrayList<>();
        subTasks.add(subTask(1, "查询业务数据", task, Set.of(2),
                List.of("可供筛选的原始数据（名称、价格、分类、图片链接等）"), queryTools));
        subTasks.add(subTask(2, "按用户需求筛选并给出建议",
                "结合任务1查询到的数据，按用户的需求筛选并给出可选建议：" + task,
                Set.of(), List.of(), List.of()));
        return toJson(Map.of(PROP_DECOMPOSE, subTasks));
    }

    private static Map<String, Object> subTask(int taskId,
                                               String taskName,
                                               String taskContent,
                                               Set<Integer> downstreamTaskIds,
                                               List<String> coreContent,
                                               List<String> toolNames) {
        Map<String, Object> subTask = new LinkedHashMap<>();
        subTask.put("taskId", taskId);
        subTask.put("taskName", taskName);
        subTask.put("taskContent", taskContent);
        subTask.put("downstreamTaskIds", downstreamTaskIds);
        subTask.put("coreContent", coreContent);
        subTask.put("toolNames", toolNames);
        return subTask;
    }

    /**
     * 子任务 ReAct 执行：演示模式不调用真实模型与外部工具，
     * 直接给出占位说明并调用 {@code assignmentFinish} 结束本子任务。
     * <p>
     * 为什么必须显式结束：{@code BaseAgent#run} 的循环条件是「未达到最大步数且未 FINISHED」，
     * 而 {@code AgentState.FINISHED} 只有执行 {@code assignmentFinish} 才会被置位。
     * 若只返回纯文本（无工具调用），ReAct 会空转到 5 步上限，同一段文案会在前端重复推送 5 次。
     */
    private static AssistantMessage finishSubTask() {
        return AssistantMessage.builder()
                .content(TAG + "免 Key 演示模式：本步不调用真实模型与外部工具，直接声明子任务完成。")
                // 真实模型走思考模式时会把推理过程放在这个元数据字段，这里同步给出一段说明，
                // 避免 ToolCallAgent 读到 null 后推出一条内容为 null 的 SSE 思考事件
                .properties(Map.of("reasoningContent", TAG + "演示模式跳过真实推理与工具调用。"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "mock-finish-" + System.nanoTime(), "function", TOOL_FINISH, "{}")))
                .build();
    }

    /**
     * 结果汇总：回显提示词里已注入的子任务结果，让「汇总」这一步的数据流向在免 Key 演示中可见。
     */
    private String fuseResults(String promptText) {
        String received = sectionAfterHeading(promptText, MARKER_FUSE, MARKER_FAILED);
        return TAG + "本次请求已走完「路由 → 规划 → 执行 → 蒸馏 → 汇总」链路，"
                + "下面是汇总阶段收到的子任务结果；接入真实 API Key 后，这里会是 LLM 生成的完整答复。\n"
                + received;
    }

    // ==================== 提示词解析辅助 ====================

    /**
     * 取出本次的用户输入：即提示词里最后一条 {@link MessageType#USER} 消息，并剥掉框架追加的
     * {@linkplain #FORMAT_TRAILER 输出格式要求}。
     * <p>
     * 分类调用点的用户消息形如「用户问题：xxx\n历史对话：…」，按标记截取；
     * 其余调用点的用户消息就是用户原话（技能选择）或上一阶段产出的总任务（任务拆分）。
     * <p>
     * 不用 {@code Prompt#getContents()}：其拼接结果在部分调用点并不包含用户消息
     * （实测技能选择调用只拼到 system 消息），据此取「最后一行」会把 JSON Schema 当成用户输入，
     * 导致关键词匹配永远落空、业务问题被降级成简单对话（该场景已由 MockChatModelTest 覆盖）。
     */
    private static String userInput(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message.getMessageType() != MessageType.USER || message.getText() == null) {
                continue;
            }
            String text = message.getText();
            int start = text.indexOf(MARKER_USER_QUESTION);
            if (start >= 0) {
                String tail = text.substring(start + MARKER_USER_QUESTION.length());
                int end = tail.indexOf(MARKER_HISTORY);
                text = (end >= 0 ? tail.substring(0, end) : tail);
            }
            return stripFormatTrailer(text);
        }
        return "";
    }

    /**
     * 剥掉 {@link BeanOutputConverter} 等结构化输出转换器追加在提示词末位的格式说明
     * （含内嵌的 JSON Schema），只保留调用方真正传入的内容。
     */
    private static String stripFormatTrailer(String text) {
        int cut = text.indexOf(FORMAT_TRAILER);
        return (cut >= 0 ? text.substring(0, cut) : text).strip();
    }

    /** 取小节标题之后、下一个标记之前的内容；替代 {@code section} 的「标题不含冒号」写法 */
    private static String sectionAfterHeading(String text, String heading, String nextMarker) {
        int start = text.indexOf(heading);
        if (start < 0) {
            return "";
        }
        String tail = text.substring(start + heading.length());
        // 标题后可能紧跟全角或半角冒号（模板里写成「【子任务结果列表】：」），统一跳过
        tail = tail.replaceFirst("^[ \\t]*[:：]", "");
        int end = tail.indexOf(nextMarker);
        return (end >= 0 ? tail.substring(0, end) : tail).strip();
    }

    /** 按关键词表匹配技能，并过滤掉提示词中不存在的技能名 */
    private static List<String> matchSkills(String question, Set<String> available) {
        if (question == null || question.isBlank() || available.isEmpty()) {
            return List.of();
        }
        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SKILL_KEYWORDS.entrySet()) {
            if (available.contains(entry.getKey()) && containsAny(question, entry.getValue())) {
                hit.add(entry.getKey());
            }
        }
        return hit;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static AssistantMessage text(String content) {
        return new AssistantMessage(content);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 只序列化 Map / List / String，理论上不会走到这里；
            // 兜底返回空 JSON 对象，让下游走「解析成功但字段为空」的降级分支，而不是中断整轮对话
            return "{}";
        }
    }
}
