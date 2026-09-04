package com.agentorchestrator.platform.common;

/**
 * 用户角色常量。
 * <p>
 * 当前仅两级：普通用户（默认）与管理员。管理员由配置白名单（{@code app.admin.usernames}）
 * 在登录时判定，用于保护 {@code /ai/admin/**} 管理端接口。
 */
public final class UserRole {

    /** 管理员角色，可访问管理后台 */
    public static final String ADMIN = "admin";

    /** 普通用户角色（默认） */
    public static final String USER = "user";

    private UserRole() {
    }
}
