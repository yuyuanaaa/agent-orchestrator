package com.kanodays88.agentplatform.service.impl;

import cn.hutool.json.JSONUtil;
import com.kanodays88.agentplatform.constant.FileConstant;
import com.kanodays88.agentplatform.content.BaseContent;
import com.kanodays88.agentplatform.service.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class FileUploadServiceImpl implements FileUploadService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public boolean save(String chatId, Resource resource) {
        // 1.保存到本地磁盘（同名文件直接覆盖，保证磁盘与向量库内容一致）
        String filename = resource.getFilename();//获取文件名字
        File target = new File(Paths.get(FileConstant.FILE_SAVE_DIR,BaseContent.getUser().getUserName(),chatId,Objects.requireNonNull(filename)).toString());
        try {
            // 关键修复：创建所有不存在的父目录
            Files.createDirectories(target.toPath().getParent());
            Files.copy(resource.getInputStream(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to save PDF resource.", e);
            return false;
        }
        // 2.先删除该会话下同名文件的旧向量，再写入新向量，避免重复上传导致检索结果重复
        deleteFromVectorStore(filename, chatId);
        writeToVectorStore(resource, chatId);
        return true;
    }

    public void writeToVectorStore(Resource resource, String chatId) {
        // 1.创建PDF的读取器
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource, // 文件源
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1) // 每1页PDF作为一个Document
                        .build()
        );
        // 2.读取PDF文档，拆分为Document
        List<Document> documents = reader.read();
        String filename = resource.getFilename();
        documents.forEach(document -> {
            document.getMetadata().put("user", BaseContent.getUser().getUserName());
            document.getMetadata().put("chat_id",chatId);
            document.getMetadata().put("filename", filename);
        });
        // 3.写入向量库
        vectorStore.add(documents);
    }

    @Override
    public boolean delete(String chatId, String fileName) {
        String userName = BaseContent.getUser().getUserName();
        // 1. 删除磁盘文件（不存在时视为已删除，返回成功，保持幂等）
        Path filePath = Paths.get(FileConstant.FILE_SAVE_DIR, userName, chatId, fileName);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除上传文件失败：{}", fileName, e);
            return false;
        }
        // 2. 删除向量库中该文件的向量（按 user + chat_id + filename 精确过滤，不影响同会话其他文件）
        deleteFromVectorStore(fileName, chatId);
        return true;
    }

    /**
     * 删除指定会话下同名文件的旧向量（按 user + chat_id + filename 三个元数据维度精确过滤，
     * 避免删除同会话其他文件，也不会误删其他用户的向量）
     */
    public void deleteFromVectorStore(String filename, String chatId) {
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        // and 仅支持二元，三个条件需嵌套：and( and(user, chat_id), filename )
        Filter.Expression expression = filter.and(
                filter.and(
                        filter.eq("user", BaseContent.getUser().getUserName()),
                        filter.eq("chat_id", chatId)),
                filter.eq("filename", filename)).build();
        vectorStore.delete(expression);
    }
}
