package com.kanodays88.agentplatform.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 生成静态资源的外网访问地址。
 * <p>
 * ServletUriComponentsBuilder 需要依赖当前 Web 请求上下文：它需要知道本次请求是 http 还是 https、
 * 域名与端口是什么，才能拼出浏览器可直接访问的地址。因此在异步线程中调用时，
 * 必须先把主线程的 RequestAttributes 传递过去（见 ChatController#executeSse）。
 */
@Slf4j
@Component
public class HttpPathUtil {

    /**
     * 网关/反向代理统一添加的前缀。
     * <p>
     * 若部署在反向代理之后且代理会为所有请求加 /api 前缀，这里保持一致即可；
     * 本地直连后端时把它置为空字符串。
     * 声明为 static：writeHttpUrl 被静态调用（工具类无实例持有方），
     * 前缀在 Spring 容器启动构造本 Bean 时一次性写入。
     */
    private static String apiPrefix = "";

    public HttpPathUtil(@Value("${app.url.api-prefix:}") String configuredPrefix) {
        apiPrefix = configuredPrefix == null ? "" : configuredPrefix;
    }

    /**
     * 基于当前请求上下文，把站内相对路径拼成完整可访问的地址
     *
     * @param path 站内相对路径，例如 /upload/xxx.jpg
     */
    public static String writeHttpUrl(String path) {
        String normalized = (path == null) ? "" : path;
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(apiPrefix + normalized)
                .toUriString();
    }
}
