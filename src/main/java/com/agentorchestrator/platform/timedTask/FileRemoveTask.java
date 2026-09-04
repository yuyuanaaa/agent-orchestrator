package com.agentorchestrator.platform.timedTask;

import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.utils.DirectoryCleaner;
import com.agentorchestrator.platform.utils.UserFilePath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 会话数据清理任务。
 * <p>
 * 会话记忆的过期时间由 ChatController 写入 Redis（key: chatMemory:{用户名}:{会话id}），
 * 这里每 2 小时扫描一次，清理过期会话的本地文件、对话记忆与向量库数据。
 */
@Component
@EnableAsync
@Slf4j
public class FileRemoveTask {

    private static final String CHAT_MEMORY_CLEANUP_SET = "remove:chatMemory";

    /** 会话记忆数据 key 前缀（与 ChatController 的会话 id 列表 key 一致） */
    private static final String CHAT_MEMORY_DATA_PREFIX = "chat:memory:";
    private static final String CHAT_LIST_KEY_PREFIX = "chat:list:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VectorStore vectorStore;

    @Scheduled(cron = "0 0 */2 * * ?")
    @Async
    public void fileRemove() {
        Set<String> chatMemoryKeys = stringRedisTemplate.opsForSet().members(CHAT_MEMORY_CLEANUP_SET);
        if (chatMemoryKeys == null || chatMemoryKeys.isEmpty()) {
            return;
        }

        for (String key : chatMemoryKeys) {
            String expireAt = stringRedisTemplate.opsForValue().get(key);
            if (expireAt == null || expireAt.isBlank()) {
                // 过期时间已丢失，直接清理索引，避免集合无限增长
                stringRedisTemplate.opsForSet().remove(CHAT_MEMORY_CLEANUP_SET, key);
                continue;
            }

            LocalDateTime timeoutTime;
            try {
                timeoutTime = LocalDateTime.parse(expireAt);
            } catch (DateTimeParseException e) {
                log.warn("会话过期时间格式异常, key={}, value={}", key, expireAt);
                stringRedisTemplate.opsForSet().remove(CHAT_MEMORY_CLEANUP_SET, key);
                continue;
            }

            if (LocalDateTime.now().isBefore(timeoutTime)) {
                continue;
            }

            log.info("会话缓存已过期，开始清理: {}", key);
            // key 格式：chatMemory:{用户名}:{会话id}，会话 id 自身可能包含冒号，因此限制切分份数
            String[] parts = key.split(":", 3);
            if (parts.length < 3) {
                log.warn("会话缓存 key 格式异常: {}", key);
                stringRedisTemplate.opsForSet().remove(CHAT_MEMORY_CLEANUP_SET, key);
                continue;
            }
            String userName = parts[1];
            String chatId = parts[2];

            // userName/chatId 来自 Redis key 拆段（split(":", 3)），
            // 零信任：先走 UserFilePath 白名单校验，不合法直接跳过避免脏数据污染磁盘
            java.nio.file.Path sessionDir;
            try {
                sessionDir = UserFilePath.resolveSessionDir(userName, chatId);
            } catch (com.agentorchestrator.platform.exception.BusinessException e) {
                log.warn("清理过期会话跳过非法 key: {} ({})", key, e.getMessage());
                stringRedisTemplate.opsForSet().remove(CHAT_MEMORY_CLEANUP_SET, key);
                continue;
            }
            DirectoryCleaner.deleteRecursively(sessionDir);

            // 会话记忆已迁移到 Redis，删除对应数据 key 与用户会话 id 列表中的记录
            stringRedisTemplate.delete(CHAT_MEMORY_DATA_PREFIX + userName + ":" + chatId);
            stringRedisTemplate.opsForSet().remove(CHAT_LIST_KEY_PREFIX + userName, chatId);

            // 按 user + chat_id 两个元数据维度过滤，只删除当前会话自己的向量，避免误删其他用户数据
            FilterExpressionBuilder filter = new FilterExpressionBuilder();
            Filter.Expression expression = filter.and(
                    filter.eq("user", userName),
                    filter.eq("chat_id", chatId)).build();
            vectorStore.delete(expression);

            stringRedisTemplate.delete(key);
            stringRedisTemplate.opsForSet().remove(CHAT_MEMORY_CLEANUP_SET, key);
        }
    }
}
