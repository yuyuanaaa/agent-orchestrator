package com.kanodays88.agentplatform;

import com.kanodays88.agentplatform.constant.FileConstant;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

@SpringBootApplication(exclude = {
        OpenAiAudioSpeechAutoConfiguration.class,      // 语音合成
        OpenAiAudioTranscriptionAutoConfiguration.class, // 语音转文字
        OpenAiImageAutoConfiguration.class,            // 图像生成
        OpenAiModerationAutoConfiguration.class        // 内容审核
})
@MapperScan("com.kanodays88.agentplatform.mapper")
@EnableCaching
@EnableScheduling
@Slf4j
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }

    //配置向量数据库，基于 SimpleVectorStore 的向量数据库
    // 返回具体类型 SimpleVectorStore，便于其他组件调用 save/load 做快照持久化（仍是 VectorStore 的子类）
    @Bean
    public SimpleVectorStore vectorStore(OpenAiEmbeddingModel model){
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(model).build();

        // 若存在历史快照则恢复，避免重启后向量数据丢失（内置知识 + 用户上传 PDF 的向量）
        File snapshot = new File(FileConstant.VECTOR_STORE_SNAPSHOT);
        if (snapshot.exists() && snapshot.length() > 0) {
            try {
                vectorStore.load(snapshot);
                log.info("已从快照加载向量库：{}", snapshot.getAbsolutePath());
            } catch (Exception e) {
                // 快照损坏时删除，回退到空向量库，由 CommonUtils 重新写入内置知识
                log.warn("向量库快照加载失败，将重建：{}", snapshot.getAbsolutePath(), e);
                snapshot.delete();
            }
        }
        return vectorStore;
    }
}
