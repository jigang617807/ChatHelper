package com.example.demo.service;

import ai.z.openapi.service.embedding.Embedding;
import com.example.demo.entity.DocumentChunk;
import com.example.demo.repository.DocumentChunkProjection;
import com.example.demo.repository.DocumentChunkRepository;
import com.example.demo.repository.DocumentChunkVectorHitProjection;
import com.example.demo.search.ChunkSearchDoc;
import com.example.demo.search.ChunkSearchRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentChunkRepository chunkRepo;
    private final ChunkSearchRepository chunkSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;


//    @Value("${zhipu.api-key}")
    @Value("${ZHIPU_API_KEY}")
    private String apiKey;

    @Value("${zhipu.embedding-model}")
    private String embeddingModel;

    @Value("${rag.retrieval.vector-topk:20}")
    private int vectorTopK;

    @Value("${rag.retrieval.bm25-topk:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.final-topk:5}")
    private int finalTopK;

    @Value("${rag.retrieval.fusion.strategy:rrf}")
    private String fusionStrategy;

    @Value("${rag.retrieval.fusion.rrf-k:60}")
    private int rrfK;

    @Value("${rag.retrieval.fusion.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${rag.retrieval.fusion.bm25-weight:0.4}")
    private double bm25Weight;

    @Value("${rag.retrieval.rerank.enabled:false}")
    private boolean rerankEnabled;

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
        // OLD: 单路向量召回
        // List<Double> qvec = embedding(question);
        // String vectorStr = qvec.toString();
        // return chunkRepo.searchSimilar(documentId, vectorStr, 5);

        // NEW: 双路召回（Vector + BM25）+ 应用层融合（RRF/加权）
        List<RankedChunk> vectorHits = vectorRecall(documentId, question, vectorTopK);
        List<RankedChunk> bm25Hits = bm25Recall(documentId, question, bm25TopK);

        List<RankedChunk> fused;
        if ("weighted".equalsIgnoreCase(fusionStrategy)) {
            fused = weightedFuse(vectorHits, bm25Hits, vectorWeight, bm25Weight);
        } else {
            fused = rrfFuse(vectorHits, bm25Hits, rrfK);
        }

        if (rerankEnabled) {
            // 预留：可选 rerank（当前保持原序，后续可替换为模型重排）
            fused = rerankPassThrough(fused, question);
        }

        return fused.stream()
                .limit(finalTopK)
                .map(hit -> (DocumentChunkProjection) () -> hit.text())
                .toList();
    }

    private List<RankedChunk> vectorRecall(Long documentId, String question, int topK) {
        List<Double> qvec = embedding(question);
        String vectorStr = qvec.toString();
        List<DocumentChunkVectorHitProjection> rows = chunkRepo.searchSimilarWithDistance(documentId, vectorStr, topK);

        List<RankedChunk> hits = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunkVectorHitProjection row = rows.get(i);
            double distance = row.getDistance() == null ? 1.0 : row.getDistance();
            // 距离越小越好，转换为可比较的“越大越好”分数
            double score = 1.0 / (1.0 + distance);
            hits.add(new RankedChunk(row.getId(), row.getText(), score, i + 1, SourceType.VECTOR));
        }
        return hits;
    }

    private List<RankedChunk> bm25Recall(Long documentId, String question, int topK) {
        try {
            Query boolQuery = Query.of(q -> q.bool(b -> b
                    .must(m -> m.match(mm -> mm.field("text").query(question)))
                    .filter(f -> f.term(t -> t.field("documentId").value(documentId)))
            ));

            Pageable pageable = PageRequest.of(0, topK);
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(boolQuery)
                    .withPageable(pageable)
                    .build();

            SearchHits<ChunkSearchDoc> searchHits = elasticsearchOperations.search(nativeQuery, ChunkSearchDoc.class);

            List<RankedChunk> hits = new ArrayList<>();
            int rank = 1;
            for (SearchHit<ChunkSearchDoc> hit : searchHits) {
                ChunkSearchDoc content = hit.getContent();
                Long chunkId = content.getChunkId();
                if (chunkId == null) {
                    continue;
                }
                float rawScore = hit.getScore();
                double score = rawScore;
                hits.add(new RankedChunk(chunkId, content.getText(), score, rank++, SourceType.BM25));
            }
            return hits;
        } catch (Exception ex) {
            // OLD: BM25 发生异常时会直接抛出，导致整次问答失败
            // NEW: 自动降级为空结果，保留向量召回链路可用
            log.warn("BM25 recall fallback to vector-only. documentId={}, reason={}", documentId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RankedChunk> rrfFuse(List<RankedChunk> vectorHits, List<RankedChunk> bm25Hits, int k) {
        Map<Long, FusionAccumulator> acc = new LinkedHashMap<>();
        addRrf(acc, vectorHits, k);
        addRrf(acc, bm25Hits, k);

        return acc.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::score).reversed())
                .map(FusionAccumulator::toRankedChunk)
                .toList();
    }

    private void addRrf(Map<Long, FusionAccumulator> acc, List<RankedChunk> hits, int k) {
        for (RankedChunk hit : hits) {
            double addScore = 1.0 / (k + hit.rank());
            acc.compute(hit.chunkId(), (id, old) -> {
                if (old == null) {
                    return FusionAccumulator.from(hit, addScore);
                }
                return old.merge(addScore, hit);
            });
        }
    }

    private List<RankedChunk> weightedFuse(List<RankedChunk> vectorHits,
                                           List<RankedChunk> bm25Hits,
                                           double vWeight,
                                           double bWeight) {
        Map<Long, FusionAccumulator> acc = new LinkedHashMap<>();

        for (RankedChunk hit : vectorHits) {
            double addScore = hit.score() * vWeight;
            acc.compute(hit.chunkId(), (id, old) -> old == null ? FusionAccumulator.from(hit, addScore) : old.merge(addScore, hit));
        }
        for (RankedChunk hit : bm25Hits) {
            double addScore = hit.score() * bWeight;
            acc.compute(hit.chunkId(), (id, old) -> old == null ? FusionAccumulator.from(hit, addScore) : old.merge(addScore, hit));
        }

        return acc.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::score).reversed())
                .map(FusionAccumulator::toRankedChunk)
                .toList();
    }

    private List<RankedChunk> rerankPassThrough(List<RankedChunk> fused, String question) {
        return fused;
    }

    private enum SourceType {
        VECTOR,
        BM25
    }

    private record RankedChunk(Long chunkId, String text, double score, int rank, SourceType sourceType) {
    }

    private record FusionAccumulator(Long chunkId, String text, double score) {
        private static FusionAccumulator from(RankedChunk hit, double initScore) {
            return new FusionAccumulator(hit.chunkId(), hit.text(), initScore);
        }

        private FusionAccumulator merge(double addScore, RankedChunk fallback) {
            String candidateText = (this.text == null || this.text.isBlank()) ? fallback.text() : this.text;
            return new FusionAccumulator(this.chunkId, candidateText, this.score + addScore);
        }

        private RankedChunk toRankedChunk() {
            return new RankedChunk(chunkId, text, score, 1, SourceType.VECTOR);
        }
    }
}
