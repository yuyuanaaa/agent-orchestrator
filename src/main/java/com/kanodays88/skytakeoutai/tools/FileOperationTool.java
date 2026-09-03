package com.kanodays88.skytakeoutai.tools;

import cn.hutool.core.io.FileUtil;
import com.kanodays88.skytakeoutai.constant.FileConstant;
import com.kanodays88.skytakeoutai.utils.HttpPathUtil;
import com.kanodays88.skytakeoutai.utils.UserFilePath;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 会话内文件读写工具。
 * <p>
 * 所有文件都限制在当前用户的当前会话目录下，文件名经 {@link UserFilePath} 校验后才落盘。
 */
@Component
public class FileOperationTool {

    private final HttpPathUtil httpPathUtil;

    public FileOperationTool(HttpPathUtil httpPathUtil) {
        this.httpPathUtil = httpPathUtil;
    }

    @Tool(description = "读取当前会话目录中已存在文件的内容，传入文件名即可，例如 report.txt")
    public String readFile(@ToolParam(description = "要读取的文件名，不含路径，例如 report.txt") String fileName) {
        try {
            Path filePath = UserFilePath.resolveInSessionDir(fileName);
            if (!FileUtil.exist(filePath.toFile())) {
                return "文件不存在: " + fileName;
            }
            return FileUtil.readUtf8String(filePath.toFile());
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    @Tool(description = "把文本内容写入当前会话目录，返回可访问的文件链接")
    public String writeFile(
            @ToolParam(description = "要写入的文件名，不含路径，例如 report.txt") String fileName,
            @ToolParam(description = "要写入的文本内容") String content) {
        try {
            Path filePath = UserFilePath.resolveInSessionDir(fileName);
            FileUtil.mkdir(filePath.getParent().toFile());
            FileUtil.writeUtf8String(content, filePath.toFile());

            // 生成可访问链接，路径与 MVCConfig 中的 /files/** 静态资源映射保持一致
            String relativePath = Paths.get(FileConstant.FILE_SAVE_DIR)
                    .toAbsolutePath()
                    .normalize()
                    .relativize(filePath)
                    .toString()
                    .replace(File.separatorChar, '/');
            return "文件写入成功，访问地址: " + httpPathUtil.writeHttpUrl("/files/" + relativePath);
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }
}
