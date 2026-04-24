package com.example.demo.service;

import com.example.demo.repository.DocumentChunkProjection;
import com.example.demo.repository.DocumentChunkRepository;
import com.example.demo.repository.DocumentChunkVectorHitProjection;
import com.example.demo.search.ChunkSearchDoc;
import com.example.demo.search.ChunkSearchRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentChunkRepository chunkRepo;
    private final ChunkSearchRepository chunkSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final SpringAiService springAiService;

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

    public List<Double> embedding(String text) {
        return springAiService.embedding(text);
    }

    public List<DocumentChunkProjection> searchRelevant(Long documentId, String question) {
        List<RankedChunk> vectorHits = vectorRecall(documentId, question, vectorTopK);
        List<RankedChunk> bm25Hits = bm25Recall(documentId, question, bm25TopK);

        List<RankedChunk> fused;
        if ("weighted".equalsIgnoreCase(fusionStrategy)) {
            fused = weightedFuse(vectorHits, bm25Hits, vectorWeight, bm25Weight);
        } else {
            fused = rrfFuse(vectorHits, bm25Hits, rrfK);
        }

        if (rerankEnabled) {
            fused = rerankPassThrough(fused, question);
        }

        return fused.stream()
                .limit(finalTopK)
                .map(hit -> (DocumentChunkProjection) hit::text)
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
                double score = hit.getScore();
                hits.add(new RankedChunk(chunkId, content.getText(), score, rank++, SourceType.BM25));
            }
            return hits;
        } catch (Exception ex) {
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
