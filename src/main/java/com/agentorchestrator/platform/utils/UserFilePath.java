package com.agentorchestrator.platform.utils;

import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.constant.FileConstant;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.exception.BusinessException;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 当前会话的文件目录解析工具。
 * <p>
 * 文件名可能由大模型生成（属于不可信输入），因此这里统一做路径穿越校验：
 * 解析后的规范路径必须以会话目录为前缀，否则拒绝访问。
 */
public final class UserFilePath {

    private UserFilePath() {
    }

    /**
     * 当前用户当前会话的文件目录：{FILE_SAVE_DIR}/{用户名}/{会话id}/file
     */
    public static Path sessionFileDir() {
        return Paths.get(FileConstant.FILE_SAVE_DIR, currentUserName(), currentChatId(), "file");
    }

    /**
     * 在会话目录内安全解析文件名。
     *
     * @param fileName 文件名（可能来自大模型，视为不可信输入）
     * @return 校验通过的绝对路径
     * @throws BusinessException 文件名非法或存在路径穿越
     */
    public static Path resolveInSessionDir(String fileName) {
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

        Path baseDir = sessionFileDir().toAbsolutePath().normalize();
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
        return BaseContent.getUser().getUserName();
    }

    private static String currentChatId() {
        String chatId = BaseContent.getChatId();
        if (chatId == null || chatId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少会话 id");
        }
        return chatId;
    }
}
