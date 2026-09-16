package com.agentorchestrator.platform.config.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MockChatModel} 的调用点分派回归测试。
 * <p>
 * 免 Key 演示（{@code mock} profile）依赖本类按调用点返回合规内容：
 * 三处结构化输出必须是能被 {@code BeanOutputConverter} 解析的 JSON，
 * 子任务 ReAct 必须主动收尾，否则真实链路会退化成「解析失败降级 / 空转到最大步数」。
 * 这里直接用生产代码同一个 {@code BeanOutputConverter} 校验，避免测试与实现各写一套「同构」结构后失真。
 * <p>
 * <b>关键：构造提示词必须带上 {@code getFormat()}。</b>
 * 生产环境里 {@code .entity(converter)} 会把「输出格式要求 + JSON Schema」追加到用户消息末尾；
 * 早先的测试只传了干净的用户消息，因此「schema 被当成用户输入」这个线上问题测不出来。
 * 现在统一通过 {@link #withFormatTrailer} 复现真实渲染。
 * <p>
 * 本测试不依赖 Spring 容器、MySQL 与 Redis。
 */
class MockChatModelTest {

    private final MockChatModel model = new MockChatModel();

    /** 与 RouterAgent.SkillSelection 结构一致 */
    record SkillSelection(List<String> skillNames) {
    }

    /** 与 RouterAgent.ClassifyResult 结构一致 */
    record ClassifyResult(String questionType, String reason, String returnQuestion, String mainTask) {
    }

    /** 与 PlanExecute.SubTask 结构一致 */
    record SubTask(int taskId,
                   String taskName,
                   String taskContent,
                   Set<Integer> downstreamTaskIds,
                   Set<String> coreContent,
                   Set<String> toolNames) {
    }

    /** 与 PlanExecute.DecomposedTasks 结构一致 */
    record DecomposedTasks(List<SubTask> subTaskList) {
    }

    @Test
    @DisplayName("技能选择：返回提示词中真实存在且与用户输入匹配的技能名")
    void selectSkillsReturnsMatchedSkillNames() {
        BeanOutputConverter<SkillSelection> converter = new BeanOutputConverter<>(SkillSelection.class);
        String system = """
                从以下可用技能中选择最匹配的业务技能：
                 - dish_and_setmeal_query: 帮助用户查询菜品或套餐信息
                 - place_order: 帮助用户完成下单
                json输出格式：%s
                """.formatted(converter.getJsonSchema());

        SkillSelection result = converter.convert(
                reply(system, withFormatTrailer("帮我推荐几道招牌菜", converter)));

        assertNotNull(result);
        assertEquals(List.of("dish_and_setmeal_query"), result.skillNames());
    }

    @Test
    @DisplayName("意图分类：命中业务技能时判定为复杂任务，并携带用户问题作为总任务")
    void classifyReturnsComplexTaskWhenSkillMatched() {
        BeanOutputConverter<ClassifyResult> converter = new BeanOutputConverter<>(ClassifyResult.class);
        // 技能标题前的空格是实际渲染结果：提示词模板代入 skillContext 时会带上一个前导空白，
        // 因此识别技能标题的正则必须容忍行首缩进（否则会误判为「未匹配到技能」）
        String system = """
                【匹配到以下业务技能】
                 ## Skill: dish_and_setmeal_query
                - Domain: commerce
                - Description: 帮助用户查询菜品或套餐信息
                具体json格式：%s
                """.formatted(converter.getJsonSchema());

        ClassifyResult result = converter.convert(reply(system,
                withFormatTrailer(classifyUserMessage("帮我推荐几道招牌菜"), converter)));

        assertNotNull(result);
        assertEquals("COMPLEX_TASK", result.questionType());
        assertTrue(result.mainTask().contains("招牌菜"));
        assertEquals("", result.returnQuestion());
        // 总任务里不得混入 schema 文本，否则会被 ReAct 提示词回显、进而污染分派
        assertFalse(result.mainTask().contains("JSON Schema"));
    }

    @Test
    @DisplayName("意图分类：未匹配到技能时判定为简单对话")
    void classifyReturnsSimpleChatWhenNoSkillMatched() {
        BeanOutputConverter<ClassifyResult> converter = new BeanOutputConverter<>(ClassifyResult.class);
        String system = """
                【匹配到以下业务技能】
                未匹配到技能
                具体json格式：%s
                """.formatted(converter.getJsonSchema());

        ClassifyResult result = converter.convert(reply(system,
                withFormatTrailer(classifyUserMessage("你好呀"), converter)));

        assertNotNull(result);
        assertEquals("SIMPLE_CHAT", result.questionType());
    }

    @Test
    @DisplayName("任务拆分：返回两个子任务，子任务2 依赖子任务1 且子任务1 挂载餐饮查询工具")
    void decomposeReturnsTwoSubtasksWithDependency() {
        BeanOutputConverter<DecomposedTasks> converter = new BeanOutputConverter<>(DecomposedTasks.class);
        String system = "输出格式要求：" + converter.getJsonSchema();
        String user = "按业务技能「dish_and_setmeal_query」的流程处理用户请求：帮我推荐几道招牌菜";

        DecomposedTasks tasks = converter.convert(reply(system, withFormatTrailer(user, converter)));

        assertNotNull(tasks);
        assertEquals(2, tasks.subTaskList().size());
        SubTask first = tasks.subTaskList().get(0);
        assertEquals(1, first.taskId());
        assertEquals(Set.of(2), first.downstreamTaskIds());
        assertTrue(first.toolNames().contains("queryDish"));
        assertTrue(tasks.subTaskList().get(1).downstreamTaskIds().isEmpty());
    }

    @Test
    @DisplayName("任务拆分：剥掉框架追加的 JSON Schema，taskContent 只保留调用方传入的任务")
    void decomposeStripsFrameworkFormatTrailer() {
        // 回归：早先把「用户消息 + getFormat()（内含 JSON Schema）」整段当成 taskContent，
        // 结果 taskContent 长达几 KB、且夹带 subTaskList 属性名，让子任务 ReAct 被误判成任务拆分
        BeanOutputConverter<DecomposedTasks> converter = new BeanOutputConverter<>(DecomposedTasks.class);
        String system = "输出格式要求：" + converter.getJsonSchema();
        String task = "按业务技能「dish_and_setmeal_query」的流程处理用户请求：帮我推荐几道招牌菜";

        DecomposedTasks tasks = converter.convert(reply(system, withFormatTrailer(task, converter)));

        assertNotNull(tasks);
        SubTask first = tasks.subTaskList().get(0);
        assertEquals(task, first.taskContent());
        assertFalse(first.taskContent().contains("subTaskList"));
        assertFalse(first.taskContent().contains("JSON Schema"));
        // 子任务2 的 taskContent 是「前缀 + 任务」，同样不应夹带 schema
        assertTrue(tasks.subTaskList().get(1).taskContent().endsWith(task));
    }

    @Test
    @DisplayName("任务拆分：旅游类任务挂载联网搜索工具而非餐饮查询工具")
    void decomposeUsesWebSearchToolForTravelTask() {
        BeanOutputConverter<DecomposedTasks> converter = new BeanOutputConverter<>(DecomposedTasks.class);
        DecomposedTasks tasks = converter.convert(reply("输出格式要求：" + converter.getJsonSchema(),
                withFormatTrailer("帮我做一个上海三日游行程", converter)));

        assertNotNull(tasks);
        assertTrue(tasks.subTaskList().get(0).toolNames().contains("batchWebSearch"));
        assertFalse(tasks.subTaskList().get(0).toolNames().contains("queryDish"));
    }

    @Test
    @DisplayName("子任务 ReAct：主动调用 assignmentFinish 收尾，并带上可读的思考说明")
    void reactReturnsFinishToolCall() {
        AssistantMessage message = call(reactSystemPrompt("帮我推荐几道招牌菜"));

        assertEquals(1, message.getToolCalls().size());
        assertEquals("assignmentFinish", message.getToolCalls().get(0).name());
        assertNotNull(message.getMetadata().get("reasoningContent"));
        assertTrue(message.getText().contains("免 Key 演示模式"));
    }

    @Test
    @DisplayName("子任务 ReAct：当前任务里夹带 subTaskList 字段时仍返回 assignmentFinish")
    void reactWinsOverStructuredMarkersInTaskContent() {
        // 回归：线上曾出现「ReAct 提示词同时命中 subTaskList」→ 被误判成任务拆分 →
        // 返回 JSON 而非工具调用 → 子任务空转到最大步数（前端看到 5 段重复文案 + 69KB 响应）。
        // 这里锁定「ReAct 优先于所有结构化标记」的分派顺序。
        AssistantMessage message = call(reactSystemPrompt(
                "{\"subTaskList\":[{\"taskId\":1,\"taskName\":\"查询业务数据\","
                        + "\"taskContent\":\"帮我推荐几道招牌菜\"}]}"));

        assertEquals(1, message.getToolCalls().size());
        assertEquals("assignmentFinish", message.getToolCalls().get(0).name());
    }

    @Test
    @DisplayName("汇总回显子任务结果；其余非结构化调用返回带 Mock 标识的占位文案")
    void fuseEchoesSubtaskResultsAndOtherCallsReturnPlaceholder() {
        String fusePrompt = """
                {role}
                基于以下子任务的执行结果，整合成最终完整的任务报告返回给用户。
                【核心目标】：帮我推荐几道招牌菜
                【子任务结果列表】：
                Step 1: 工具 assignmentFinish 完成了它的任务！结果: 任务结束
                """;
        String fused = call(fusePrompt).getText();
        assertTrue(fused.contains("Step 1: 工具 assignmentFinish"));
        assertTrue(fused.contains("[Mock 模式]"));
        // 回归保护：汇总提示词里带着子任务的工具执行结果，不能被误判成子任务执行而返回收尾工具调用
        assertFalse(fused.contains("直接声明子任务完成"));

        String plain = call("你是商家服务平台的智能客服助手，回答用户的问题：你好呀").getText();
        assertTrue(plain.contains("[Mock 模式]"));
    }

    /**
     * 复现生产渲染：{@code .entity(converter)} 会把 {@code getFormat()}（输出格式说明 + JSON Schema）
     * 追加到用户消息末尾，分隔符为 {@code \r\n}（实测于 Windows 下的真实请求）。
     * 测试必须带上它，否则「schema 被当作用户输入」这类问题测不出来。
     */
    private static String withFormatTrailer(String userText, StructuredOutputConverter<?> converter) {
        return userText + "\r\n" + converter.getFormat();
    }

    /** 与 ToolCallAgent.think() 中构造的系统提示词保持一致 */
    private static String reactSystemPrompt(String taskContent) {
        return """
                角色：使用工具专注完成当前任务的AI助手
                核心规则：
                1. 结合历史对话且只能使用提供的工具完成当前任务
                2. 当判断任务完成或无法继续时，立即调用assignmentFinish工具
                3. 当前剩余思考次数：5 / 5 ;你需要在思考次数耗尽时尽可能完成任务
                4. 仅可输出调用工具的信息，不得输出其他结论
                当前任务：%s
                """.formatted(taskContent);
    }

    /** 构造分类调用点的用户消息（与 RouterAgent.llmClassify 的拼接格式一致） */
    private static String classifyUserMessage(String question) {
        return "用户问题：" + question + "\n历史对话：\n无历史对话";
    }

    /** 按 system → user 的顺序构造 Prompt 并取回模型的文本输出 */
    private String reply(String systemText, String userText) {
        return call(systemText, userText).getText();
    }

    private AssistantMessage call(String systemText) {
        return call(systemText, "你好呀");
    }

    private AssistantMessage call(String systemText, String userText) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemText), new UserMessage(userText)));
        return model.call(prompt).getResult().getOutput();
    }
}
