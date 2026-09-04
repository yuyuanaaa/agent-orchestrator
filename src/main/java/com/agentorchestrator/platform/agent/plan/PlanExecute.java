package com.agentorchestrator.platform.agent.plan;


import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentorchestrator.platform.advisor.MyLoggerAdvisor;
import com.agentorchestrator.platform.agent.AgentFactory;
import com.agentorchestrator.platform.agent.PlanAgent;
import com.agentorchestrator.platform.agent.sse.SSESend;
import com.agentorchestrator.platform.common.ChatSystem;
import com.agentorchestrator.platform.constant.FileConstant;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.entity.dto.UserLoginDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;



/**
 * 3. 任务分解结果：带全局约束的子任务列表
 */
record DecomposedTasks(
        // 带契约的子任务列表
        List<SubTask> subTaskList
) {}

/**
 * 4. 蒸馏结果双副本：结构化结果进上下文，原始结果留存用于排查与后续召回
 */
record DistilledResult(
        // 所属子任务ID
        int taskId,
        // 【进上下文】结构化蒸馏后的核心结果（token压缩90%+）
        String structuredCoreResult,
        // 【不进上下文】子任务原始完整结果（留存用于排查与后续召回）
        String rawResult,
        // 子任务契约（用于下游校验）
        SubTask subTask
) {}




@Component
@Slf4j
public class PlanExecute {

    private ChatClient chatClient;


    private OpenAiChatModel openAiChatModel;

    @Autowired
    private ToolCallback[] allTools;

    /** Agent 创建工厂，收敛实例化入口 */
    private final AgentFactory agentFactory;

    /** wave 子任务线程池（见 AsyncConfig.waveExecutor），与 SSE 主任务池隔离，避免嵌套提交死锁 */
    private final Executor waveExecutor;

    public PlanExecute(OpenAiChatModel openAiChatModel,
                       AgentFactory agentFactory,
                       @Qualifier("waveExecutor") Executor waveExecutor) throws IOException {
        this.openAiChatModel = openAiChatModel;
        this.agentFactory = agentFactory;
        this.waveExecutor = waveExecutor;
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(
                new MyLoggerAdvisor()
        ).build();
    }
    //计划执行，整个智能体执行的入口
    public String planExecute(String originalTask, String conversationId, SseEmitter emitter) throws IOException {
        long overallStart = System.currentTimeMillis();//获取系统时间
        //由于任务并行执行时会额外开启一次异步线程，所以需要传递一下线程上下文（本质将Web线程上下文传递到任务执行线程）
        //获取当前线程的上下文
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        //获取当前线程的chatId
        String chatId = BaseContent.getChatId();
        //获取当前线程的登录用户
        UserLoginDTO userLoginDTO = BaseContent.getUser();

        //对意图进行任务拆分
        if(!SSESend.sendEventThink(emitter,"开始对任务进行拆分...\n")) return null;
        long t0 = System.currentTimeMillis();
        DecomposedTasks decomposedTasks = decomposeTaskWithContract(originalTask);
        log.info("[Phase] decomposeTask took {} ms", System.currentTimeMillis() - t0);
        List<SubTask> subTasks = extractSubTasks(decomposedTasks, originalTask);
        String taskMessage = subTasks.stream().map(s -> {
            return "任务" + s.taskId() + "：" + s.taskName();
        }).collect(Collectors.joining("\n---\n"));
        if(!SSESend.sendEventThink(emitter,"任务拆分完成：\n"+taskMessage)) return null;
        //对每个子任务执行，得到结果集（并行wave执行）
        //ConcurrentHashMap线程安全hashMap，内部使用乐观锁或悲观锁的形式实现线程安全
        //原理简单来说就是put操作时，对对应的hash桶上锁，写入操作完成后才释放锁供其他线程操作
        ConcurrentHashMap<Integer, DistilledResult> resultMap = new ConcurrentHashMap<>();
        List<Set<Integer>> waves = buildExecutionWaves(subTasks);
        Map<Integer, SubTask> taskMap = subTasks.stream()
                .collect(Collectors.toMap(SubTask::taskId, Function.identity()));

        for (Set<Integer> waveTaskIds : waves) {
            //本波次的异步任务集合，提交到共享线程池（见 AsyncConfig）
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int taskId : waveTaskIds) {
                SubTask task = taskMap.get(taskId);
                //从共享线程池中创建异步任务，并加入到集合
                futures.add(CompletableFuture.runAsync(() -> {
                        //将主线程上下文设置到当前线程上下文
                        if(attributes != null){
                            //主要用于获取主线程上下文，从而获取到Web请求线程（主线程）的HttpServletRequest的请求信息
                            RequestContextHolder.setRequestAttributes(attributes);
                        }
                        //设置该线程的chatId
                        BaseContent.setChatId(chatId);
                        //设置当前异步线程的登录用户
                        BaseContent.setUser(userLoginDTO);
                        long taskStart = System.currentTimeMillis();
                        log.info("[Phase] Starting subtask {}: {}", task.taskId(), task.taskName());
                        try {
                            ToolCallback[] tools = getTools(task.toolNames());
                            PlanAgent subTaskAgent = agentFactory.createManus(tools);
                            SSESend.sendEventThink(emitter,"开始执行任务【" + task.taskName() + "】\n");
                            //获取该任务对应所需的上游任务的结果
                            String upStreamTaskResult = checkAndFillUpstreamContext(task, resultMap);
                            //将上游的结果作为记忆输入给智能体
                            subTaskAgent.setMessageList(List.of(upStreamTaskResult).stream()
                                    .map(s -> new SystemMessage(s)).collect(Collectors.toList()));

                            //执行任务，得到本次任务的原始结果
                            long tRun = System.currentTimeMillis();
                            List<String> childResult = subTaskAgent.run(task.taskContent(), task.taskName(), emitter);
                            log.info("[Phase] Subtask {} run() took {} ms", task.taskId(), System.currentTimeMillis() - tRun);
                            //原始结果拼接
                            String result = childResult.stream().collect(Collectors.joining("/n---/n"));
                            //蒸馏任务结果
                            long tDistill = System.currentTimeMillis();
                            DistilledResult distilledResult;
                            if (result.length() < 2000) {
                                distilledResult = new DistilledResult(task.taskId(), result, result, task);
                                log.info("[Optimize] Skipped distill for task {} (already structured)", task.taskName());
                            } else {
                                distilledResult = distillSubTaskResult(task, result);
                            }
                            log.info("[Phase] Subtask {} distill took {} ms", task.taskId(), System.currentTimeMillis() - tDistill);

                            resultMap.put(task.taskId(), distilledResult);
                        } catch (Exception e) {
                            log.error("[Optimize] Subtask {} failed: {}", task.taskId(), e.getMessage());
                        } finally {
                            //删除ThreadLocal防止内存泄露与线程复用串号
                            BaseContent.removeChatId();
                            BaseContent.removeUser();
                            //释放ThreadLocal
                            RequestContextHolder.resetRequestAttributes();
                        }
                }, waveExecutor));
            }
            //阻塞等待本波次全部子任务完成，下一波次的输入依赖本波次的执行结果；
            //orTimeout(60s) 兜底：子任务池即使被打满也不永久阻塞，超时抛异常由上游捕获
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).orTimeout(60, TimeUnit.SECONDS).join();
        }

        //整合结果集和意图，得到最终结果
        long tFuse = System.currentTimeMillis();
        List<DistilledResult> orderedResults = subTasks.stream()
                .map(t -> resultMap.get(t.taskId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        String s = fuseResults(originalTask, orderedResults);
        log.info("[Phase] fuseResults took {} ms", System.currentTimeMillis() - tFuse);
        log.info("[Phase] TOTAL planExecute took {} ms", System.currentTimeMillis() - overallStart);
        return s;
    }



    /**
     * 从任务分解结果中提取子任务列表。
     * LLM 结构化输出可能解析失败（返回 null）或给出空列表，此时降级为
     * 「单子任务直接执行原始任务」，避免下游 NPE 导致整个链路失败。
     * 包可见：供单元测试直接验证（见 PlanExecuteTest）。
     */
    List<SubTask> extractSubTasks(DecomposedTasks decomposedTasks, String originalTask) {
        if (decomposedTasks != null
                && decomposedTasks.subTaskList() != null
                && !decomposedTasks.subTaskList().isEmpty()) {
            return decomposedTasks.subTaskList();
        }
        log.warn("任务分解结果为空，降级为单子任务直接执行: {}", originalTask);
        return List.of(new SubTask(
                1,
                "原始任务",
                originalTask,
                Set.of(),
                Set.of(),
                Set.of() // 空集合表示不限制工具，getTools 会放行全部
        ));
    }

    //从所需工具名称集合中获取到具体工具集合；未指定工具时放行全部工具
    public ToolCallback[] getTools(Set<String> toolNames){
        if (toolNames == null || toolNames.isEmpty()) {
            return allTools;
        }
        ToolCallback[] toolCallbacks = Arrays.stream(allTools).filter(t -> (toolNames.contains(t.getToolDefinition().name())||t.getToolDefinition().name().equals("assignmentFinish"))).toArray(ToolCallback[]::new);
        return toolCallbacks;
    }
    //任务分解
    public DecomposedTasks decomposeTaskWithContract(String task) {
        BeanOutputConverter<DecomposedTasks> converter = new BeanOutputConverter<>(DecomposedTasks.class);
        String prompt = """
            ##任务生成规则
            生成带依赖契约的结构化结果，严格遵守规则：
            1. 为每个子任务定义完整契约：
               - taskId：子任务序号，从1开始递增
               - taskName：子任务名称
               - taskContent：子任务具体执行内容
               - downstreamTaskIds：所有会用到该子任务结果的下游任务的taskId（不止紧邻的下一个任务，必须覆盖所有下游依赖）
               - coreContent：所有下游任务要求该子任务必须输出的核心内容（比如图片链接，店铺地址等）
               - toolNames：子任务执行需要调用的工具名称
            2. 子任务依赖关系必须清晰，确保所有下游需要的核心内容提前定义，不能出现下游需要的内容上游没输出的情况。
            3. 工具信息只做任务划分参考，不得使用工具
            4. 不得生成无工具的任务
            5. 生成任务不得带有总结类性质,fuseResult智能体会将所有子任务汇合总结展示给用户

            输出格式要求：{format}
            """;
//        String userPrompt = """
//                    核心目标：{mainGoal}
//                    约束条件：{constraints}
//                    交付要求：{deliverables}
//                """;

        return chatClient.prompt()
                .system(s -> s.text(prompt)
                        .param("format", converter.getJsonSchema()))
                .user(task)
                .toolCallbacks(allTools)
                .call()
                .entity(converter);
    }

    /**
     * 构建任务层，同一层任务可以并行执行。
     * 包可见：供单元测试直接验证（见 PlanExecuteTest）。
     * @param subTasks
     * @return
     */
    List<Set<Integer>> buildExecutionWaves(List<SubTask> subTasks) {
        // dependsOn: for each task, which upstream taskIds it depends on
        Map<Integer, Set<Integer>> dependsOn = new HashMap<>();
        Map<Integer, SubTask> taskMap = new HashMap<>();

        for (SubTask task : subTasks) {
            taskMap.put(task.taskId(), task);
            dependsOn.put(task.taskId(), new HashSet<>());
        }

        // For each task, find tasks whose downstreamTaskIds contain this task's ID
        // Those are the tasks this task depends on
        for (SubTask task : subTasks) {
            for (SubTask other : subTasks) {
                if (other.downstreamTaskIds().contains(task.taskId())) {
                    dependsOn.get(task.taskId()).add(other.taskId());
                }
            }
        }

        List<Set<Integer>> waves = new ArrayList<>();
        Set<Integer> remaining = new HashSet<>(taskMap.keySet());

        while (!remaining.isEmpty()) {
            Set<Integer> wave = new HashSet<>();
            for (int taskId : remaining) {
                if (dependsOn.get(taskId).isEmpty()) {
                    wave.add(taskId);
                }
            }

            if (wave.isEmpty()) {
                // Circular dependency fallback: add all remaining as one wave
                wave.addAll(remaining);
            }

            waves.add(wave);
            remaining.removeAll(wave);

            // Remove completed tasks from dependency sets
            for (int taskId : remaining) {
                dependsOn.get(taskId).removeAll(wave);
            }
        }

        return waves;
    }

//    /**
//     * 选取上游任务的蒸馏后结果，若有问题则提取原结果重新蒸馏
//     * @param currentTask 当前任务
//     * @param upstreamResults  上游任务列表
//     * @return
//     */
//    private String checkAndFillUpstreamContext(SubTask currentTask, List<DistilledResult> upstreamResults) {
//        StringBuilder context = new StringBuilder();
//        // 没有上游依赖，直接返回空
//        if (upstreamResults.isEmpty()) return "";
//
//        // 遍历所有上游结果，校验当前任务需要的字段是否完整
//        for (DistilledResult upstreamResult : upstreamResults) {
//            // 只处理当前任务依赖的上游任务
//            if (!upstreamResult.subTask().downstreamTaskIds().contains(currentTask.taskId())) continue;
//
//            SubTask upstreamTask = upstreamResult.subTask();//获取上游任务
//            Set<String> requiredFields = upstreamTask.requiredFields();
//            String coreResult = upstreamResult.structuredCoreResult();
//
//            // 把校验后的上游核心结果加入上下文
//            context.append("上游任务:").append(upstreamTask.taskContent()).append("\n核心结果:").append(coreResult).append("\n---\n");
//        }
//        return context.toString();
//    }

    /**
     * 选取上游任务结果
     * @param currentTask 当前任务
     * @param upstreamResults  上游任务列表
     * @return
     */
    private String checkAndFillUpstreamContext(SubTask currentTask, Map<Integer, DistilledResult> upstreamResults) {
        StringBuilder context = new StringBuilder();
        if (upstreamResults.isEmpty()) return "";

        for (DistilledResult upstreamResult : upstreamResults.values()) {
            if (!upstreamResult.subTask().downstreamTaskIds().contains(currentTask.taskId())) continue;

            SubTask upstreamTask = upstreamResult.subTask();
            String coreResult = upstreamResult.structuredCoreResult();

            context.append("上游任务:").append(upstreamTask.taskContent()).append("\n核心结果:").append(coreResult).append("\n---\n");
        }
        return context.toString();
    }

    /**
     * 任务蒸馏

    /**
     * 任务蒸馏
     * @param subTask 原任务
     * @param rawResult 原任务返回结果
     * @param globalRequiredFields 全局任务必须要求的字段
     * @return
     */
    private DistilledResult distillSubTaskResult(SubTask subTask, String rawResult) {
        String prompt = """
            对用户输入的子任务的原始结果做蒸馏，严格遵守以下规则，违规直接输出无效：
            1. 不得删除下游任务需要的所有核心内容：{coreContent}
            2. 删除所有与核心内容无关的内容
            """;
        String userPrompt = """
                子任务名称：{taskName}
                子任务原始结果：{rawResult}
                """;

        // 结构化蒸馏，强制符合Schema，保证必填字段不丢
        String structuredCoreResult = chatClient.prompt()
                .system(s -> s.text(prompt)
                        .param("coreContent", subTask.coreContent()))
                .user(u->u.text(userPrompt).param("taskName",subTask.taskName()).param("rawResult",rawResult))
                .call()
                .content();
        return new DistilledResult(subTask.taskId(), structuredCoreResult, rawResult, subTask);
    }

    //整合结果集和意图，得到最终结果
    private String fuseResults(String task, List<DistilledResult> subTaskResults) {
        List<String> results = subTaskResults.stream().map(s -> s.structuredCoreResult()).collect(Collectors.toList());
        String prompt = """
            {role}
            基于以下子任务的执行结果，整合成最终完整的任务报告返回给用户。
            不要遗漏任何子任务的关键结果。
            【核心目标】：{mainGoal}
            【子任务结果列表】：
            {subTaskResults}
            """;

        return chatClient.prompt()
                .system(s -> s.text(prompt)
                        .param("role",ChatSystem.CHAT_SYSTEM)
                        .param("mainGoal", task)
                        .param("subTaskResults", String.join("\n---\n", results)))
                .call()
                .content();
    }

}


















