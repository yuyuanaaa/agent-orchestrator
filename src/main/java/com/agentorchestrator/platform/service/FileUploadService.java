package com.agentorchestrator.platform.service;

import org.springframework.core.io.Resource;

public interface FileUploadService {
    boolean save(String chatId, Resource resource);

    /**
     * 删除指定会话下已上传的文件（同时清理磁盘文件与向量库中该文件的向量）
     *
     * @param chatId   会话 id
     * @param fileName 文件名（含扩展名）
     * @return 删除是否成功
     */
    boolean delete(String chatId, String fileName);
}
