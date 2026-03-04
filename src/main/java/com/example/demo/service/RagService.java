package com.example.demo.service;

import ai.z.openapi.service.embedding.Embedding;
import com.example.demo.entity.DocumentChunk;
import com.example.demo.repository.DocumentChunkRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

import ai.z.openapi.ZhipuAiClient;
//官方文档有误
import com.example.demo.repository.DocumentChunkProjection;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class RagService {

    private final DocumentChunkRepository chunkRepo;


//    @Value("${zhipu.api-key}")
    @Value("${ZHIPU_API_KEY}")
    private String apiKey;

    @Value("${zhipu.embedding-model}")
    private String embeddingModel;

//    private final ZhipuAiClient zhipuClient = ZhipuAiClient.builder()
//            .apiKey(apiKey)
//            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
//            .build();

    private ZhipuAiClient zhipuClient;
    @PostConstruct
    public void init() {
        this.zhipuClient = ZhipuAiClient.builder()
                .apiKey(apiKey)
                .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
                .build();
    }

    public List<Double> embedding(String text) {

        // 1. 创建 Embedding 请求（参考了 Embedding3Example 的构建方式）
        EmbeddingCreateParams request = EmbeddingCreateParams.builder()
                .model(embeddingModel) // 使用配置文件中的模型名
                .input(Collections.singletonList(text)) // 将单个文本封装成 List<String>
                .dimensions(768) // 维度是可选的，这里省略，使用模型默认值，如果需要自定义，则取消注释
                .build();

        // 2. 发送请求（参考client.embeddings().createEmbeddings(request) 的调用方式）
        // SDK 调用是同步的，可以直接获取响应
        EmbeddingResponse response = zhipuClient.embeddings().createEmbeddings(request);
        Embedding embeddingObject = response.getData().getData().get(0);
        return embeddingObject.getEmbedding();
    }




    public List<DocumentChunkProjection> searchRelevant(Long documentId, String question) {
        List<Double> qvec = embedding(question);

        // 将 List<Double> 转换为 JSON 格式字符串 "[0.1, 0.2, ...]"
        String vectorStr = qvec.toString();

        // 直接调用数据库进行向量检索
        // limit=5: 只要前5个最相关的
        return chunkRepo.searchSimilar(documentId, vectorStr, 5);
    }
}
