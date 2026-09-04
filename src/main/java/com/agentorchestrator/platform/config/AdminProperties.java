package com.agentorchestrator.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 管理员白名单配置。
 * <p>
 * 从 {@code app.admin.usernames}（逗号分隔）解析管理员用户名集合，
 * 登录时据此判定用户角色（见 UserServiceImpl）。支持环境变量 {@code ADMIN_USERNAMES} 覆盖，
 * 便于 Docker / 生产环境注入真实管理员账号。
 */
@Component
public class AdminProperties {

    private final Set<String> usernames;

    public AdminProperties(@Value("${app.admin.usernames:}") String configured) {
        this.usernames = new HashSet<>();
        if (configured != null && !configured.isBlank()) {
            for (String name : configured.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    usernames.add(trimmed);
                }
            }
        }
    }

    /**
     * 判断给定用户名是否为管理员。
     *
     * @param userName 登录用户名
     * @return true 表示管理员
     */
    public boolean isAdmin(String userName) {
        return userName != null && usernames.contains(userName);
    }
}
