package com.kanodays88.skytakeoutai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 智能体执行线程池（全局唯一，Spring 统一管理）。
 * <p>
 * 此前两处各自为政：ChatController 用 {@code CompletableFuture.runAsync} 落到
 * 公共 ForkJoinPool（无命名、无界并行、无法监控）；PlanExecute 每个 wave
 * {@code Executors.newFixedThreadPool(n)} 新建线程池用完即弃（线程不复用、不可观测、
 * 应用关闭时不等待任务完成）。统一收敛到本 Bean：
 * <ul>
 *   <li>线程命名 {@code agent-exec-N}，日志与线程 dump 中可识别</li>
 *   <li>有界队列 + CallerRunsPolicy：过载时回压到提交线程而非丢任务或 OOM</li>
 *   <li>优雅停机：等待最长 30 秒让在途任务跑完</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

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
}
