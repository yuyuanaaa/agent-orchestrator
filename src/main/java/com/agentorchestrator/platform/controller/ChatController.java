package com.agentorchestrator.platform.controller;

import com.agentorchestrator.platform.agent.AgentFactory;
import com.agentorchestrator.platform.agent.plan.PlanExecute;
import com.agentorchestrator.platform.agent.router.QuestionType;
import com.agentorchestrator.platform.agent.router.RouteDecisionTotal;
import com.agentorchestrator.platform.agent.router.RouterAgent;
import com.agentorchestrator.platform.agent.simpleChat.SimpleChatAgent;
import com.agentorchestrator.platform.agent.sse.SSESend;
import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.common.ChatRateLimiter;
import com.agentorchestrator.platform.common.LLMCircuitBreaker;
import com.agentorchestrator.platform.common.Result;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.entity.dto.UserLoginDTO;
import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.memory.RedisChatMemory;
import com.agentorchestrator.platform.utils.DirectoryCleaner;
import com.agentorchestrator.platform.utils.UserFilePath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体对话入口。
 * <p>
 * 请求链路：{@link RouterAgent} 意图路由 → 简单对话 / 复杂任务规划执行 / 反问补全。
 * 复杂任务由 {@link PlanExecute} 拆解为子任务并行执行，全过程通过 SSE 向前端推送思考过程与最终结果。
 * <p>
 * 注意：SSE 接口在异步线程中执行，异常不会被 GlobalExceptionHandler 捕获，
 * 因此这里统一在 finally 中推送错误事件并关闭连接。
 */
@RestController
@RequestMapping("/ai/chat")
@CrossOrigin
@Slf4j
public class ChatController {

    /** SSE 连接超时时间 */
    private static final long SSE_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    /** 会话记忆保留时长，过期后由 FileRemoveTask 清理 */
    private static final Duration CHAT_MEMORY_TTL = Duration.ofHours(24);

    private static final String CHAT_MEMORY_KEY_PREFIX = "chatMemory:";
    private static final String CHAT_MEMORY_CLEANUP_SET = "remove:chatMemory";

    /** 会话 id 列表 key（按用户聚合，供历史会话列表接口读取） */
    private static final String CHAT_LIST_KEY_PREFIX = "chat:list:";

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PlanExecute planExecute;

    @Autowired
    private AgentFactory agentFactory;

    /** 智能体执行线程池（见 AsyncConfig），替代默认 ForkJoinPool */
    @Autowired
    @Qualifier("agentExecutor")
    private ThreadPoolTaskExecutor agentExecutor;

    /** 对话限流器（Redis 固定窗口计数，单用户 QPS 保护） */
    @Autowired
    private ChatRateLimiter chatRateLimiter;

    /** LLM 调用熔断器（连续失败快速降级，避免请求雪崩） */
    @Autowired
    private LLMCircuitBreaker llmCircuitBreaker;

    /**
     * 智能体对话主入口（自动意图路由）
     */
    @GetMapping(value = "/{msg}", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@PathVariable("msg") String msg, @RequestHeader("chatId") String chatId) {
        if (msg == null || msg.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "提问内容不能为空");
        }
        // 单用户限流：同步阶段检查，超限直接抛异常返回错误，不进入 SSE 异步链路
        chatRateLimiter.checkRateLimit(currentUserName());
        return executeSse(chatId, (emitter, memory) -> {
            // 进入 RouterAgent 做意图路由
            RouterAgent routerAgent = agentFactory.createRouterAgent(emitter);
            RouteDecisionTotal route = routerAgent.route(msg, chatId);

            String aiResult;
            if (route.decision().questionType() == QuestionType.SIMPLE_CHAT) {
                SimpleChatAgent simpleChatAgent = agentFactory.createSimpleChatAgent();
                aiResult = simpleChatAgent.simpleChat(msg, chatId);
            } else if (route.decision().questionType() == QuestionType.COMPLEX_TASK) {
                aiResult = planExecute.planExecute(route.decision().mianTask(), chatId, emitter);
            } else if (route.decision().questionType() == QuestionType.AMBIGUOUS) {
                // 关键信息缺失或意图不明确，反问用户
                aiResult = route.decision().returnQuestion();
            } else {
                aiResult = "未能识别您的问题类型，请换个说法再试一次。";
            }

            if (aiResult == null) {
                aiResult = "任务执行未产出了结果，请稍后重试。";
            }
            SSESend.sendEventResult(emitter, aiResult);
            return aiResult;
        }, (emitter, aiResult) -> persistChatMemory(chatId, msg, aiResult));
    }

    /**
     * 基于用户上传 PDF 的 RAG 问答（不经过意图路由，直接按会话维度检索知识库）
     */
    @GetMapping(value = "/rag/{msg}", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter rag(@PathVariable("msg") String msg, @RequestHeader("chatId") String chatId) {
        if (msg == null || msg.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "提问内容不能为空");
        }
        // 单用户限流：同 chat 入口
        chatRateLimiter.checkRateLimit(currentUserName());
        return executeSse(chatId, (emitter, memory) -> {
            SSESend.sendEventThink(emitter, "开始检索知识库关联内容\n");
            String ragContent = searchKnowledgeBase(msg, chatId);
            SSESend.sendEventThink(emitter, "检索完成\n");

            PromptTemplate promptTemplate = new PromptTemplate("""
                    ## 根据额外信息回答用户提问，当额外信息为 null 时，提醒用户尚未上传 PDF 文档
                    【用户提问】
                    {userMessage}
                    【额外信息】
                    {ragDocuments}
                    """);
            Prompt prompt = promptTemplate.create(Map.of(
                    "userMessage", msg,
                    "ragDocuments", (ragContent == null || ragContent.isEmpty()) ? "null" : ragContent));

            SimpleChatAgent simpleChatAgent = agentFactory.createSimpleChatAgent();
            String answer = simpleChatAgent.simpleChat(prompt.getContents(), chatId);
            SSESend.sendEventResult(emitter, answer);
            return answer;
        }, (emitter, answer) -> persistChatMemory(chatId, msg, answer));
    }

    /**
     * 查询当前用户的全部会话 id
     */
    @GetMapping("/history")
    public Result<String[]> historyQuery() {
        Set<String> chatIds = stringRedisTemplate.opsForSet().members(CHAT_LIST_KEY_PREFIX + currentUserName());
        if (chatIds == null || chatIds.isEmpty()) {
            return Result.success(new String[0]);
        }
        return Result.success(chatIds.toArray(new String[0]));
    }

    /**
     * 查询指定会话的历史消息
     */
    @GetMapping("/history/{chatId}")
    public Result<List<String>> historyQueryByChatId(@PathVariable("chatId") String chatId) throws IOException {
        List<Message> allMemory = chatMemory().getAll(chatId);
        return Result.success(allMemory.stream()
                .map(m -> m.getMessageType() + ":" + m.getText())
                .collect(Collectors.toList()));
    }

    /**
     * 删除指定会话的对话记忆与本地文件
     */
    @DeleteMapping("/history/{chatId}")
    public Result<String> historyRemove(@PathVariable("chatId") String chatId) throws IOException {
        chatMemory().clear(chatId);
        stringRedisTemplate.opsForSet().remove(CHAT_LIST_KEY_PREFIX + currentUserName(), chatId);
        // chatId 来自 @PathVariable，零信任：先白名单校验再解析为绝对路径，
        // 校验通过后该路径必然落在 FILE_SAVE_DIR 下，可安全递归删除
        DirectoryCleaner.deleteRecursively(UserFilePath.resolveSessionDir(currentUserName(), chatId));
        return Result.success("删除成功");
    }

    // ============================ 内部实现 ============================

    /**
     * SSE 通用执行模板：负责线程上下文传递、ThreadLocal 清理、异常兜底与连接关闭。
     *
     * @param chatId     会话 id
     * @param task       真正要执行的智能体逻辑，返回要给用户的最终结果
     * @param afterTask  成功执行后的收尾逻辑，如写入会话记忆
     */
    private SseEmitter executeSse(String chatId,
                                  SseTask task,
                                  SseAfterTask afterTask) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        UserLoginDTO currentUser = BaseContent.getUser();
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String chatIdInThread = BaseContent.getChatId() != null ? BaseContent.getChatId() : chatId;

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        // 提交到统一管理的智能体线程池（见 AsyncConfig），而非公共 ForkJoinPool
        agentExecutor.execute(() -> {
            try {
                // 将主线程的请求上下文与登录态传递到异步线程，否则工具层拿不到 HttpServletRequest 与用户信息
                if (attributes != null) {
                    RequestContextHolder.setRequestAttributes(attributes);
                }
                BaseContent.setChatId(chatIdInThread);
                BaseContent.setUser(currentUser);

                // LLM 熔断：熔断期间快速失败降级，不把请求继续打到下游模型
                if (!llmCircuitBreaker.allowRequest()) {
                    log.warn("LLM 熔断中，降级返回 chatId={}", chatIdInThread);
                    SSESend.sendEventResult(emitter, "AI 服务暂时不可用，请稍后重试");
                    return;
                }

                String aiResult = task.execute(emitter, chatMemory());
                // 链路成功，重置熔断失败计数
                llmCircuitBreaker.recordSuccess();
                if (aiResult != null) {
                    afterTask.execute(emitter, aiResult);
                }
            } catch (Exception e) {
                log.error("对话执行异常, chatId={}", chatIdInThread, e);
                // 记录失败供熔断器统计，连续失败达到阈值后自动熔断
                llmCircuitBreaker.recordFailure();
                SSESend.sendEventResult(emitter, "执行失败: " + e.getMessage());
                emitter.completeWithError(e);
            } finally {
                // 释放 ThreadLocal，避免线程池复用时串号与内存泄漏
                BaseContent.removeChatId();
                BaseContent.removeUser();
                RequestContextHolder.resetRequestAttributes();
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * 按 user + chat_id 检索当前会话自己的知识库切片，实现多租户隔离
     */
    private String searchKnowledgeBase(String userMessage, String chatId) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op eqUser = filter.eq("user", currentUserName());
        FilterExpressionBuilder.Op eqChatId = filter.eq("chat_id", chatId);
        Filter.Expression expression = filter.and(eqUser, eqChatId).build();

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .filterExpression(expression)
                        .topK(5)
                        .build());
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        return documents.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
    }

    /**
     * 写入本轮对话记忆，并刷新该会话的过期时间（供 FileRemoveTask 清理）
     */
    private void persistChatMemory(String chatId, String userMessage, String aiResult) {
        try {
            chatMemory().add(chatId, List.of(new UserMessage(userMessage), new AssistantMessage(aiResult)));
            // 记录会话 id，供历史会话列表接口读取
            stringRedisTemplate.opsForSet().add(CHAT_LIST_KEY_PREFIX + currentUserName(), chatId);
            String cacheKey = CHAT_MEMORY_KEY_PREFIX + currentUserName() + ":" + chatId;
            stringRedisTemplate.opsForValue().set(cacheKey,
                    LocalDateTime.now().plus(CHAT_MEMORY_TTL).toString());
            stringRedisTemplate.opsForSet().add(CHAT_MEMORY_CLEANUP_SET, cacheKey);
        } catch (Exception e) {
            log.error("写入会话记忆失败, chatId={}", chatId, e);
        }
    }

    private RedisChatMemory chatMemory() throws IOException {
        return new RedisChatMemory(stringRedisTemplate, currentUserName());
    }

    private String currentUserName() {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getUserName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return user.getUserName();
    }

    /** 智能体执行逻辑，返回给用户的最终结果；返回 null 表示没有结果需要落记忆 */
    @FunctionalInterface
    private interface SseTask {
        String execute(SseEmitter emitter, ChatMemory memory) throws Exception;
    }

    /** 执行成功后的收尾逻辑 */
    @FunctionalInterface
    private interface SseAfterTask {
        void execute(SseEmitter emitter, String aiResult) throws Exception;
    }
}
