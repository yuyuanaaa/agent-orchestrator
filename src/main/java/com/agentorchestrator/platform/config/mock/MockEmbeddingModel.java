package com.agentorchestrator.platform.config.mock;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock 向量化模型：在 {@code mock} profile 下替代真实的 Embedding 模型，
 * 让向量库（SimpleVectorStore）与联网搜索的 RAG 二次提纯在无 Key 时也能初始化。
 * <p>
 * 向量维度固定返回 8 维全零向量，仅用于「占位」让 {@code SimpleVectorStore.builder(model)}
 * 能构造成功；mock 模式下 RAG 相似度检索无实际语义，但不影响对话链路贯通演示。
 * 生产环境（非 mock profile）不会加载本类。
 */
public class MockEmbeddingModel implements EmbeddingModel {

    /** mock 固定向量维度，任意值均可，这里取 8 减小占位开销 */
    private static final int MOCK_DIMENSIONS = 8;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(new float[MOCK_DIMENSIONS], i));
            }
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return new float[MOCK_DIMENSIONS];
    }

    @Override
    public int dimensions() {
        return MOCK_DIMENSIONS;
    }
}
