package com.agentorchestrator.platform.utils;

import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.constant.FileConstant;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.exception.BusinessException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 当前会话的文件目录解析工具。
 * <p>
 * 用户名、{@code chatId}、文件名均视为不可信输入（来自 HTTP 头、LLM 工具参数或 Redis 拆出的字段），
 * 这里统一做：
 * <ul>
 *   <li>用户名 / {@code chatId} 字符白名单（仅允许 {@code [A-Za-z0-9_-]}、长度 1-64），阻断路径穿越</li>
 *   <li>文件名黑名单（{@code ..}、{@code \0}、绝对路径、盘符）与规范化后 must-starts-with 双重校验</li>
 *   <li>解析后的绝对路径必须落在会话目录前缀内，否则拒绝访问</li>
 * </ul>
 * 所有文件相关入口（上传、生成、删除、下载、列表）必须走本工具，零信任任何上游传进来的路径片段。
 * <p>
 * 同时提供「隐式」（从 {@link BaseContent} 拿）与「显式」（调用方传入 userName/chatId）两组重载：
 * <ul>
 *   <li>隐式版本给 ChatController / PlanExecute / PDFGenerationTool 这类已经在 SSE 异步线程
 *       里设好 ThreadLocal 上下文的场景用，省事</li>
 *   <li>显式版本给 {@code @PathVariable}/{@code @RequestHeader} 进来的 HTTP 入参（chatId 不一定和
 *       BaseContent 一致，比如 {@code FileUploadController.uploadPdf}），以及定时任务从 Redis 拆段
 *       拿到的 userName/chatId 用</li>
 * </ul>
 */
public final class UserFilePath {

    private UserFilePath() {
    }

    /**
     * 用户名 / chatId 字符白名单：仅允许字母数字下划线短横线，长度 1-64。
     * <p>
     * 设计取舍：UUID/数字/短横线都能直接落库；不允许点号是怕和 URL 路由解析耦合，
     * 不允许点斜杠反斜杠冒号是直接堵住路径穿越的字符集。
     */
    private static final Pattern USER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CHAT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    // ============================ 隐式重载（从 BaseContent 拿） ============================

    /**
     * 当前用户当前会话的「file 子目录」：{FILE_SAVE_DIR}/{用户名}/{会话id}/file
     */
    public static Path sessionFileDir() {
        return Paths.get(FileConstant.FILE_SAVE_DIR, currentUserName(), currentChatId(), "file");
    }

    /** 当前用户当前会话的根目录（不带 /file）：{FILE_SAVE_DIR}/{用户名}/{会话id} */
    public static Path sessionRootDir() {
        return Paths.get(FileConstant.FILE_SAVE_DIR, currentUserName(), currentChatId());
    }

    /** 在「file 子目录」内安全解析文件名（隐式） */
    public static Path resolveInSessionDir(String fileName) {
        return resolveUnder(sessionFileDir().toAbsolutePath().normalize(), fileName);
    }

    /** 在「会话根目录」内安全解析文件名（隐式） */
    public static Path resolveInSessionRoot(String fileName) {
        return resolveUnder(sessionRootDir().toAbsolutePath().normalize(), fileName);
    }

    // ============================ 显式重载（调用方传 userName/chatId） ============================

    /**
     * 显式校验 userName + chatId 合法性，返回组合好的会话根目录绝对路径。
     * <p>
     * 用于删除、清理、列表、下载等需要"按传入的 userName/chatId 拼路径"的场景，
     * 调用方先校验，再决定是否继续操作。
     */
    public static Path resolveSessionDir(String userName, String chatId) {
        validateUserName(userName);
        validateChatId(chatId);
        return Paths.get(FileConstant.FILE_SAVE_DIR, userName, chatId).toAbsolutePath().normalize();
    }

    /** 显式在「file 子目录」内解析文件名 */
    public static Path resolveInSessionDir(String userName, String chatId, String fileName) {
        Path base = resolveSessionDir(userName, chatId).resolve("file").toAbsolutePath().normalize();
        return resolveUnder(base, fileName);
    }

    /** 显式在「会话根目录」内解析文件名 */
    public static Path resolveInSessionRoot(String userName, String chatId, String fileName) {
        Path base = resolveSessionDir(userName, chatId);
        return resolveUnder(base, fileName);
    }

    // ============================ 字段校验 ============================

    /** 用户名字符白名单：仅允许 {@code [A-Za-z0-9_-]}，长度 1-64 */
    public static void validateUserName(String userName) {
        if (userName == null || !USER_NAME_PATTERN.matcher(userName).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名不合法: " + userName);
        }
    }

    /** chatId 字符白名单：仅允许 {@code [A-Za-z0-9_-]}，长度 1-64 */
    public static void validateChatId(String chatId) {
        if (chatId == null || !CHAT_ID_PATTERN.matcher(chatId).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "会话 id 不合法: " + chatId);
        }
    }

    // ============================ 内部实现 ============================

    private static Path resolveUnder(Path baseDir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        String trimmed = fileName.trim();
        if (trimmed.contains("\0")
                || trimmed.contains("..")
                || trimmed.startsWith("/")
                || trimmed.startsWith("\\")
                || trimmed.contains(":")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不合法: " + trimmed);
        }

        Path resolved = baseDir.resolve(trimmed).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不允许访问会话目录之外的文件");
        }
        return resolved;
    }

    private static String currentUserName() {
        if (BaseContent.getUser() == null || BaseContent.getUser().getUserName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String userName = BaseContent.getUser().getUserName();
        // 业务兜底：从 ThreadLocal 拿出来的用户名也要走白名单，防止登录态被改
        validateUserName(userName);
        return userName;
    }

    private static String currentChatId() {
        String chatId = BaseContent.getChatId();
        if (chatId == null || chatId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少会话 id");
        }
        validateChatId(chatId);
        return chatId;
    }
}
