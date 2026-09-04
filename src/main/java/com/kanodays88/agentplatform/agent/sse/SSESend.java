package com.kanodays88.agentplatform.agent.sse;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SSESend {

    // ====================== 辅助方法：发送SSE事件 ======================
    public static boolean sendEventThink(SseEmitter emitter, String data) {
        return sendEvent(emitter, "Agent思考:", data);
    }

    public static boolean sendEventResult(SseEmitter emitter, String data) {
        return sendEvent(emitter, "Agent结果:", data);
    }

    /**
     * 发送带前缀的 SSE data 事件。
     * <p>
     * 关键：AI 返回的文本通常是多行的，而 {@code SseEmitter.event().data(str)} 不会自动给
     * 每一行加 "data:" 前缀。若直接整体发送，文本中的换行会破坏 SSE 帧，前端解析时会把
     * 第一个换行之后的内容全部丢弃（表现为「后端有输出、前端只显示第一行或什么都不显示」）。
     * 因此这里按行拆分、逐行调用 {@code data()}，让每一行都带 "data:" 前缀（符合 SSE 规范的多行写法），
     * 前端已有的「按行收集 data 后 join 回来」逻辑会正确还原出完整文本。
     */
    private static boolean sendEvent(SseEmitter emitter, String prefix, String data) {
        try {
            synchronized (emitter) {
                SseEmitter.SseEventBuilder builder = SseEmitter.event();
                // limit 传 -1 保留末尾空行，避免丢掉段落最后的换行；兼容 \n 与 \r\n
                for (String line : (prefix + data).split("\\r?\\n", -1)) {
                    // 强制 UTF-8，避免中文乱码
                    builder.data(line, new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
                }
                emitter.send(builder);
                return true;
            }
        } catch (IOException e) {
            // 发送失败时关闭连接
            emitter.completeWithError(e);
            return false;
        }
    }
}
