package com.agentorchestrator.platform.agent.plan;

import com.agentorchestrator.platform.skill.Skill;
import com.agentorchestrator.platform.skill.SkillLoader;
import com.agentorchestrator.platform.skill.SkillParameter;
import com.agentorchestrator.platform.skill.SkillRegistry;
import com.agentorchestrator.platform.skill.SkillStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlanExecute 核心纯逻辑单元测试（不依赖 Spring 容器与 LLM）。
 * <p>
 * 覆盖四个关键算法：
 * <ul>
 *   <li>{@code buildExecutionWaves}：依赖拓扑分层（并行波次构建）</li>
 *   <li>{@code extractSubTasks}：LLM 分解结果为空时的降级兜底</li>
 *   <li>{@code getTools}：按子任务契约筛选工具</li>
 *   <li>{@code buildSkillContext}：路由命中的技能定义注入任务分解上下文</li>
 * </ul>
 */
class PlanExecuteTest {

    private PlanExecute planExecute;

    @BeforeEach
    void setUp() throws IOException {
        // ChatClient.builder 仅持有模型引用，不发起调用，mock 即可
        planExecute = new PlanExecute(
                Mockito.mock(OpenAiChatModel.class),
                null,                                // 工厂在纯逻辑测试中不会被触达
                Runnable::run,                       // 同步执行器（waveExecutor），纯逻辑测试不真正并发
                new SkillRegistry(new SkillLoader())); // 格式化技能上下文，纯逻辑不触达文件系统
    }

    private SubTask task(int id, Set<Integer> downstream) {
        return new SubTask(id, "任务" + id, "内容" + id, downstream, Set.of(), Set.of());
    }

    // ==================== buildExecutionWaves ====================

    @Test
    @DisplayName("线性依赖链应拆成三个串行波次")
    void linearChainShouldProduceThreeWaves() {
        // 1 → 2 → 3
        List<SubTask> subTasks = List.of(
                task(1, Set.of(2)),
                task(2, Set.of(3)),
                task(3, Set.of()));

        List<Set<Integer>> waves = planExecute.buildExecutionWaves(subTasks);

        assertEquals(3, waves.size());
        assertEquals(Set.of(1), waves.get(0));
        assertEquals(Set.of(2), waves.get(1));
        assertEquals(Set.of(3), waves.get(2));
    }

    @Test
    @DisplayName("互不依赖的任务应合并到同一波次并行执行")
    void independentTasksShouldBeOneWave() {
        List<SubTask> subTasks = List.of(
                task(1, Set.of()),
                task(2, Set.of()),
                task(3, Set.of()));

        List<Set<Integer>> waves = planExecute.buildExecutionWaves(subTasks);

        assertEquals(1, waves.size());
        assertEquals(Set.of(1, 2, 3), waves.get(0));
    }

    @Test
    @DisplayName("菱形依赖（1→{2,3}→4）应拆成三波：[1] [2,3] [4]")
    void diamondDependencyShouldFanOutAndFanIn() {
        List<SubTask> subTasks = List.of(
                task(1, Set.of(2, 3)),
                task(2, Set.of(4)),
                task(3, Set.of(4)),
                task(4, Set.of()));

        List<Set<Integer>> waves = planExecute.buildExecutionWaves(subTasks);

        assertEquals(3, waves.size());
        assertEquals(Set.of(1), waves.get(0));
        assertEquals(Set.of(2, 3), waves.get(1));
        assertEquals(Set.of(4), waves.get(2));
    }

    @Test
    @DisplayName("循环依赖不应死循环，全部任务兜底进同一波次")
    void circularDependencyShouldNotInfiniteLoop() {
        List<SubTask> subTasks = List.of(
                task(1, Set.of(2)),
                task(2, Set.of(1)));

        List<Set<Integer>> waves = planExecute.buildExecutionWaves(subTasks);

        assertEquals(1, waves.size());
        assertEquals(Set.of(1, 2), waves.get(0));
    }

    @Test
    @DisplayName("上游任务失败时，依赖它的下游任务应被识别为阻塞")
    void downstreamShouldBeBlockedWhenUpstreamFailed() {
        java.util.Map<Integer, Set<Integer>> dependsOn = java.util.Map.of(
                1, Set.of(),
                2, Set.of(1),
                3, Set.of(2));

        assertTrue(planExecute.hasFailedUpstream(2, dependsOn, Set.of(1)));
        assertTrue(planExecute.hasFailedUpstream(3, dependsOn, Set.of(1, 2)));
        assertFalse(planExecute.hasFailedUpstream(1, dependsOn, Set.of(1)));
    }

    // ==================== extractSubTasks ====================

    @Test
    @DisplayName("LLM 分解结果为 null 时应降级为单子任务，不再 NPE")
    void nullDecomposedResultShouldFallbackToSingleTask() {
        List<SubTask> subTasks = planExecute.extractSubTasks(null, "帮我订一份午餐");

        assertEquals(1, subTasks.size());
        SubTask fallback = subTasks.get(0);
        assertEquals("帮我订一份午餐", fallback.taskContent());
        assertNotNull(fallback.downstreamTaskIds());
    }

    @Test
    @DisplayName("LLM 分解结果为空列表时同样降级")
    void emptyDecomposedResultShouldFallback() {
        List<SubTask> subTasks = planExecute.extractSubTasks(
                new DecomposedTasks(List.of()), "原始任务");

        assertEquals(1, subTasks.size());
        assertEquals("原始任务", subTasks.get(0).taskContent());
    }

    @Test
    @DisplayName("正常分解结果原样透传")
    void validResultShouldPassThrough() {
        List<SubTask> origin = List.of(
                task(1, Set.of(2)),
                task(2, Set.of()));
        List<SubTask> subTasks = planExecute.extractSubTasks(new DecomposedTasks(origin), "无关");

        assertEquals(origin, subTasks);
    }

    // ==================== getTools ====================

    private ToolCallback toolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        var definition = mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    @DisplayName("按契约名称筛选工具，终止工具始终随行")
    void getToolsShouldFilterByNameAndAlwaysIncludeFinish() {
        ToolCallback[] all = new ToolCallback[]{
                toolCallback("dishQuery"),
                toolCallback("setmealQuery"),
                toolCallback("assignmentFinish"),
                toolCallback("webSearch")
        };
        ReflectionTestUtils.setField(planExecute, "allTools", all);

        ToolCallback[] selected = planExecute.getTools(Set.of("dishQuery"));

        List<String> names = java.util.Arrays.stream(selected)
                .map(t -> t.getToolDefinition().name())
                .collect(Collectors.toList());
        // 契约指定的 dishQuery + 恒随行的 assignmentFinish
        assertEquals(2, names.size());
        assertTrue(names.contains("dishQuery"));
        assertTrue(names.contains("assignmentFinish"));
    }

    @Test
    @DisplayName("契约未指定工具（兜底单子任务场景）应放行全部工具")
    void emptyToolNamesShouldReturnAll() {
        ToolCallback[] all = new ToolCallback[]{
                toolCallback("dishQuery"),
                toolCallback("webSearch"),
                toolCallback("assignmentFinish")
        };
        ReflectionTestUtils.setField(planExecute, "allTools", all);

        ToolCallback[] selected = planExecute.getTools(Set.of());

        assertEquals(all.length, selected.length);
    }

    // ==================== buildSkillContext ====================

    /** 一个带完整定义（参数 / 执行流程 / 工具）的技能，用于验证注入内容是否够用 */
    private Skill dishQuerySkill() {
        return Skill.builder()
                .name("dish_and_setmeal_query")
                .domain("commerce")
                .description("查询菜品与套餐")
                .parameters(List.of(new SkillParameter("category", "string", "菜品分类", "high")))
                .steps(List.of(new SkillStep(1, "查询菜品",
                        "调用菜品查询工具获取在售菜品", List.of("queryDish"))))
                .relatedTools(List.of("queryDish", "querySetmeal"))
                .build();
    }

    @Test
    @DisplayName("命中的技能定义应完整注入分解上下文（名称 / Execution Flow / 工具 / 高重要度参数）")
    void skillContextShouldCarryFullSkillDefinition() {
        String context = planExecute.buildSkillContext(List.of(dishQuerySkill()));

        assertTrue(context.contains("## Skill: dish_and_setmeal_query"));
        assertTrue(context.contains("### Execution Flow"));
        assertTrue(context.contains("queryDish"));
        assertTrue(context.contains("importance: high"));
    }

    @Test
    @DisplayName("多技能命中时按顺序拼接，互不覆盖")
    void skillContextShouldJoinMultipleSkills() {
        String context = planExecute.buildSkillContext(List.of(
                dishQuerySkill(),
                Skill.builder().name("place_order").domain("commerce").description("下单").build()));

        assertTrue(context.contains("## Skill: dish_and_setmeal_query"));
        assertTrue(context.contains("## Skill: place_order"));
    }

    @Test
    @DisplayName("未命中技能时给占位说明，不把空上下文塞进提示词")
    void skillContextShouldFallbackWhenNothingMatched() {
        String placeholder = "本轮未命中业务技能，按通用流程拆分即可。";

        assertEquals(placeholder, planExecute.buildSkillContext(null));
        assertEquals(placeholder, planExecute.buildSkillContext(List.of()));
    }
}
