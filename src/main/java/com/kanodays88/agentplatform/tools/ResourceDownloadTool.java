package com.kanodays88.agentplatform.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.kanodays88.agentplatform.utils.UserFilePath;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 远程资源下载工具：把网络文件保存到当前用户当前会话目录下。
 */
@Component
public class ResourceDownloadTool {

    @Tool(description = "根据 URL 下载网络资源并保存到当前会话目录")
    public String downloadResource(@ToolParam(description = "要下载的资源 URL") String url,
                                   @ToolParam(description = "保存后的文件名，不含路径，例如 image.png") String fileName) {
        if (url == null || !url.matches("^(https?|ftp)://.+")) {
            return "下载失败：仅支持 http/https/ftp 协议的地址";
        }
        try {
            Path filePath = UserFilePath.resolveInSessionDir(fileName);
            FileUtil.mkdir(filePath.getParent().toFile());
            HttpUtil.downloadFile(url, filePath.toFile());
            return "资源下载成功，保存路径: " + filePath;
        } catch (Exception e) {
            return "下载资源失败: " + e.getMessage();
        }
    }
}
