package com.agentorchestrator.platform.constant;

import com.agentorchestrator.platform.content.BaseContent;

import java.nio.file.Paths;

public interface FileConstant {

    //                     获取用户工作目录
    String FILE_SAVE_DIR = Paths.get(System.getProperty("user.dir"),"tmp").toString();

    // 向量库快照文件：SimpleVectorStore 序列化落盘路径（重启时从此恢复，避免向量数据丢失）
    String VECTOR_STORE_SNAPSHOT = Paths.get(FILE_SAVE_DIR, "vector-store.json").toString();
}