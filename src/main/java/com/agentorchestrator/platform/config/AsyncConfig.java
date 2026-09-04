package com.agentorchestrator.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 智能体执行线程池（两个 Bean 分工，均由 Spring 统一管理）。
 * <p>
 * 此前两处各自为政：ChatController 用 {@code CompletableFuture.runAsync} 落到
 * 公共 ForkJoinPool（无命名、无界并行、无法监控）；PlanExecute 每个 wave
 * {@code Executors.newFixedThreadPool(n)} 新建线程池用完即弃（线程不复用、不可观测、
 * 应用关闭时不等待任务完成）。统一收敛到本 Bean：
 * <ul>
 *   <li>线程命名 {@code agent-exec-N} / {@code wave-exec-N}，日志与线程 dump 中可识别</li>
 *   <li>有界队列 + CallerRunsPolicy：过载时回压到提交线程而非丢任务或 OOM</li>
 *   <li>优雅停机：等待最长 30 秒让在途任务跑完</li>
 * </ul>
 * <p>
 * <b>为何拆两个池（防嵌套死锁）</b>：SSE 主任务与 wave 子任务若共用同一池，会出现
 * 「8 个主任务占满核心线程 → 各自 fork 出 N 个子任务进队列并 join() 等待 → 子任务
 * 等不到空闲线程 → 全部 join 阻塞 → 应用 hang 死」的嵌套提交死锁。拆分后子任务落在
 * 独立的 {@code waveExecutor} 上，不再与主任务争抢线程，配合每波
 * {@code join().orTimeout(60s)} 兜底，即使子任务池打满也能超时返回而非永久阻塞。
 */
@Configuration
public class AsyncConfig {

    /**
     * 外层 SSE 会话线程池：执行每个用户对话的完整智能体主任务
     * （路由 → 规划 → 执行 → 蒸馏 → 汇总）。
     */
    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-exec-");
        // 队列满且线程达到上限时，由提交任务的线程自己执行，形成天然背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：应用关闭时等待在途智能体任务完成，避免 SSE 连接被硬掐
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * wave 子任务线程池：执行拓扑分层中每个 wave 内部的子任务（PlanAgent）。
     * <p>
     * 与 {@link #agentExecutor()} 物理隔离，避免 SSE 主任务与 wave 子任务嵌套提交时
     * 互相占用对方线程导致死锁。核心线程数略小（子任务通常比主任务更短平快），
     * 队列容量给足以承接同一 wave 内 burst 提交的子任务。
     */
    @Bean("waveExecutor")
    public ThreadPoolTaskExecutor waveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(400);
        executor.setThreadNamePrefix("wave-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
