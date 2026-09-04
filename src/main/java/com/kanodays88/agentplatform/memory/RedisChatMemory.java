package com.kanodays88.agentplatform.memory;

import cn.hutool.core.codec.Base64;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的会话记忆实现（替代原先的本地文件实现，支持多实例共享）。
 * <p>
 * 记忆按「用户 + 会话」维度以 Redis key 存储（{@code chat:memory:{userName}:{conversationId}}），
 * 多实例共享同一 Redis 即可实现会话记忆的水平扩展，不再依赖本地磁盘文件。
 * <p>
 * 序列化沿用 Kryo（写入类型信息，可正确还原 {@code List<Message>}），
 * 二进制结果经 Base64 编码后存入 StringRedisTemplate，与项目现有 Redis 用法保持一致。
 * 过期清理由 {@link com.kanodays88.agentplatform.timedTask.FileRemoveTask} 统一负责。
 */
public class RedisChatMemory implements ChatMemory {

    /** 记忆数据 key 前缀（注意与 TTL 标记 key {@code chatMemory:} 区分） */
    private static final String KEY_PREFIX = "chat:memory:";

    /** 单次取出的最近消息条数 */
    private final int lastN = 10;

    private final StringRedisTemplate stringRedisTemplate;

    private final String userName;

    /**
     * Kryo 非线程安全，按线程隔离，避免并发写入同一会话时互相污染。
     */
    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        // 关闭手动注册，自动序列化任意对象
        kryo.setRegistrationRequired(false);
        // 采用无需无参构造器的实例化策略，兼容 Spring AI Message 实现类
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        return kryo;
    });

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate, String userName) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userName = userName;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> existing = read(conversationId);
        existing.addAll(messages);
        write(conversationId, existing);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> messages = read(conversationId);
        return messages.stream().skip(Math.max(0, messages.size() - lastN)).toList();
    }

    /** 取出该会话的全部历史消息（供历史记录接口使用，不受 lastN 限制） */
    public List<Message> getAll(String conversationId) {
        return read(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(key(conversationId));
    }

    private String key(String conversationId) {
        return KEY_PREFIX + userName + ":" + conversationId;
    }

    @SuppressWarnings("unchecked")
    private List<Message> read(String conversationId) {
        String base64 = stringRedisTemplate.opsForValue().get(key(conversationId));
        if (base64 == null || base64.isEmpty()) {
            return new ArrayList<>();
        }
        byte[] bytes = Base64.decode(base64);
        try (Input input = new Input(bytes)) {
            return (List<Message>) kryoThreadLocal.get().readObject(input, ArrayList.class);
        } catch (Exception e) {
            // 数据损坏时降级为空历史，避免阻断对话
            return new ArrayList<>();
        }
    }

    private void write(String conversationId, List<Message> messages) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Output output = new Output(baos)) {
            kryoThreadLocal.get().writeObject(output, messages);
        }
        stringRedisTemplate.opsForValue().set(key(conversationId), Base64.encode(baos.toByteArray()));
    }
}
