package com.kanodays88.skytakeoutai.agent.plan;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlanExecute 核心纯逻辑单元测试（不依赖 Spring 容器与 LLM）。
 * <p>
 * 覆盖三个关键算法：
 * <ul>
 *   <li>{@code buildExecutionWaves}：依赖拓扑分层（并行波次构建）</li>
 *   <li>{@code extractSubTasks}：LLM 分解结果为空时的降级兜底</li>
 *   <li>{@code getTools}：按子任务契约筛选工具</li>
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
                Runnable::run);                      // 同步执行器
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
}
