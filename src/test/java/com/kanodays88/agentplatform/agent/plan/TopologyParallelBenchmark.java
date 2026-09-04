package com.kanodays88.agentplatform.agent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.openai.OpenAiChatModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 基准测试：拓扑分层并行 vs 强制串行执行的耗时对比。
 * <p>
 * 模拟 N 个独立子任务（无依赖，全部归为同一波次）：
 * <ul>
 *   <li>【当前实现】同波次由共享线程池并发执行</li>
 *   <li>【反例 baseline】不构建依赖图，强制按列表顺序串行执行</li>
 * </ul>
 * 每个任务的模拟耗时通过 Thread.sleep 注入，避免受真实 LLM 调用波动影响。
 * <p>
 * 运行：mvnw test -Dtest=TopologyParallelBenchmark
 * <p>
 * 注意：本类放在 agent.plan 包下是为了访问包私有的 buildExecutionWaves 方法。
 */
class TopologyParallelBenchmark {

    private static final int TASK_COUNT = 8;          // 子任务数
    private static final long SIMULATED_TASK_MS = 200; // 每个任务模拟耗时
    private static final int WARMUP_ITERATIONS = 1;    // 预热，避免 JIT 干扰

    @Test
    @DisplayName("拓扑分层并行：N 个独立子任务，并行 vs 串行耗时")
    void measureParallelVsSerial() throws IOException, InterruptedException {
        PlanExecute planExecute = new PlanExecute(
                Mockito.mock(OpenAiChatModel.class),
                null,
                Runnable::run); // buildExecutionWaves 是纯逻辑，不需要线程池

        // 构造 N 个相互独立的子任务（无下游依赖，全部归为同一波次）
        List<SubTask> subTasks = IntStream.rangeClosed(1, TASK_COUNT)
                .mapToObj(i -> new SubTask(
                        i,
                        "任务" + i,
                        "执行内容" + i,
                        Set.of(),
                        Set.of(),
                        Set.of()))
                .collect(Collectors.toList());

        List<Set<Integer>> waves = planExecute.buildExecutionWaves(subTasks);

        System.out.println("===== 拓扑分层并行基准 =====");
        System.out.printf("任务数 = %d | 每任务模拟耗时 = %d ms | 依赖 = 无%n", TASK_COUNT, SIMULATED_TASK_MS);
        System.out.printf("实际波次数 = %d（应为 1：所有任务在同一波并发执行）%n", waves.size());
        System.out.printf("第 1 波任务数 = %d%n", waves.get(0).size());
        System.out.println();

        Executor parallelExecutor = Executors.newFixedThreadPool(
                Math.min(TASK_COUNT, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "benchmark-parallel");
                    t.setDaemon(true);
                    return t;
                });

        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runSerial(subTasks);
            runParallel(subTasks, parallelExecutor);
        }

        // 串行模式
        long serialTotal = 0;
        int serialRuns = 3;
        for (int i = 0; i < serialRuns; i++) {
            long t = runSerial(subTasks);
            serialTotal += t;
        }
        long serialAvg = serialTotal / serialRuns;

        // 并行模式（按当前实现：同波次并发）
        long parallelTotal = 0;
        int parallelRuns = 3;
        for (int i = 0; i < parallelRuns; i++) {
            long t = runParallel(subTasks, parallelExecutor);
            parallelTotal += t;
        }
        long parallelAvg = parallelTotal / parallelRuns;

        double speedup = (double) serialAvg / parallelAvg;
        long saved = serialAvg - parallelAvg;

        System.out.printf("串行平均耗时：%,d ms%n", serialAvg);
        System.out.printf("并行平均耗时：%,d ms%n", parallelAvg);
        System.out.printf("加速比：%.2fx（节省 %,d ms，约 %.0f%%）%n",
                speedup, saved, (1.0 - (double) parallelAvg / serialAvg) * 100);
        System.out.println("===== END =====");

        ((java.util.concurrent.ThreadPoolExecutor) parallelExecutor).shutdown();
    }

    /** 强制串行：按列表顺序逐个执行 */
    private long runSerial(List<SubTask> subTasks) {
        long start = System.nanoTime();
        for (SubTask task : subTasks) {
            simulateWork(task.taskId());
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** 按当前实现：构建波次 → 同波次并发、层间串行（这里只有 1 个波次） */
    private long runParallel(List<SubTask> subTasks, Executor executor) {
        long start = System.nanoTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SubTask task : subTasks) {
            futures.add(CompletableFuture.runAsync(() -> simulateWork(task.taskId()), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void simulateWork(int taskId) {
        try {
            Thread.sleep(SIMULATED_TASK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 防止 JIT 把空循环 + sleep 优化掉
        if (taskId < 0) System.out.println("unreachable");
    }
}
