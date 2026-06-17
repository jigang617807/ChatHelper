package com.example.demo.service;

import com.example.demo.repository.DocumentChunkCacheProjection;
import com.example.demo.repository.DocumentChunkRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RagRetrievalCacheService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentChunkRepository chunkRepository;

    @Value("${rag.cache.enabled:false}")
    private boolean enabled;

    @Value("${rag.cache.ttl-seconds:3600}")
    private long ttlSeconds;

    @Value("${rag.cache.key-prefix:rag:retrieval:v2}")
    private String keyPrefix;

    public Optional<List<DocumentChunkCacheProjection>> get(Long userId, Long documentId, String question) {
        return getResult(userId, documentId, question)
                .map(result -> new ArrayList<DocumentChunkCacheProjection>(result.getEvidence()));
    }

    public Optional<RagSearchResult> getResult(Long userId, Long documentId, String question) {
        if (!enabled || userId == null || documentId == null || question == null || question.isBlank()) {
            return Optional.empty();
        }

        try {
            String json = redisTemplate.opsForValue().get(cacheKey(userId, documentId, question));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }

            RetrievalCacheEntry entry = objectMapper.readValue(json, RetrievalCacheEntry.class);
            if (entry.chunks() == null || entry.chunks().isEmpty()) {
                return Optional.empty();
            }

            List<Long> chunkIds = entry.chunks().stream()
                    .map(CachedChunk::id)
                    .filter(id -> id != null)
                    .toList();
            if (chunkIds.isEmpty()) {
                return Optional.empty();
            }

            List<DocumentChunkCacheProjection> liveChunks =
                    chunkRepository.findByDocumentIdAndIdIn(documentId, chunkIds);
            if (liveChunks.isEmpty()) {
                return Optional.empty();
            }

            Map<Long, DocumentChunkCacheProjection> liveById = new HashMap<>();
            for (DocumentChunkCacheProjection chunk : liveChunks) {
                liveById.put(chunk.getId(), chunk);
            }

            List<RagChunkEvidence> evidence = new ArrayList<>();
            for (CachedChunk cached : entry.chunks()) {
                DocumentChunkCacheProjection live = liveById.get(cached.id());
                if (live == null) {
                    continue;
                }
                evidence.add(new RagChunkEvidence(
                        live.getId(),
                        live.getText(),
                        live.getChunkIndex(),
                        live.getPageNumber(),
                        live.getSectionTitle(),
                        live.getContentType(),
                        live.getSourcePath(),
                        cached.citationId(),
                        cached.sourceType(),
                        cached.score(),
                        cached.confidence(),
                        cached.vectorScore(),
                        cached.bm25Score(),
                        cached.vectorRank(),
                        cached.bm25Rank()
                ));
            }
            if (evidence.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RagSearchResult(
                    question,
                    evidence,
                    entry.confidenceScore(),
                    entry.confidenceLevel(),
                    entry.confidenceReason()
            ));
        } catch (Exception ex) {
            log.warn("RAG retrieval cache read skipped. documentId={}, reason={}", documentId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long userId,
                    Long documentId,
                    String question,
                    List<? extends DocumentChunkCacheProjection> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<RagChunkEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkCacheProjection chunk = chunks.get(i);
            evidence.add(new RagChunkEvidence(
                    chunk.getId(),
                    chunk.getText(),
                    chunk.getChunkIndex(),
                    chunk.getPageNumber(),
                    chunk.getSectionTitle(),
                    chunk.getContentType(),
                    chunk.getSourcePath(),
                    "S" + (i + 1),
                    "CACHED",
                    0.0,
                    0.5,
                    0.0,
                    0.0,
                    null,
                    null
            ));
        }
        putResult(userId, documentId, question,
                new RagSearchResult(question, evidence, 0.5, "中", "来自兼容缓存写入，未包含完整检索分数。"));
    }

    public void putResult(Long userId, Long documentId, String question, RagSearchResult result) {
        if (!enabled || userId == null || documentId == null || question == null || question.isBlank()
                || result == null || result.getEvidence().isEmpty()) {
            return;
        }

        List<Long> chunkIds = result.getEvidence().stream()
                .map(RagChunkEvidence::getId)
                .filter(id -> id != null)
                .toList();
        if (chunkIds.isEmpty()) {
            return;
        }

        List<CachedChunk> chunks = result.getEvidence().stream()
                .filter(item -> item.getId() != null)
                .map(item -> new CachedChunk(
                        item.getId(),
                        item.getCitationId(),
                        item.getSourceType(),
                        item.getScore(),
                        item.getConfidence(),
                        item.getVectorScore(),
                        item.getBm25Score(),
                        item.getVectorRank(),
                        item.getBm25Rank()
                ))
                .toList();

        RetrievalCacheEntry entry = new RetrievalCacheEntry(
                userId,
                documentId,
                normalizeQuestion(question),
                chunkIds,
                chunks,
                result.getConfidenceScore(),
                result.getConfidenceLevel(),
                result.getConfidenceReason(),
                Instant.now().toEpochMilli()
        );

        try {
            String key = cacheKey(userId, documentId, question);
            String json = objectMapper.writeValueAsString(entry);
            Duration ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
            redisTemplate.opsForValue().set(key, json, ttl);
            redisTemplate.opsForSet().add(documentIndexKey(userId, documentId), key);
            redisTemplate.expire(documentIndexKey(userId, documentId), ttl);
        } catch (JsonProcessingException ex) {
            log.warn("RAG retrieval cache serialization skipped. documentId={}, reason={}", documentId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("RAG retrieval cache write skipped. documentId={}, reason={}", documentId, ex.getMessage());
        }
    }

    public void evictDocument(Long userId, Long documentId) {
        if (!enabled || userId == null || documentId == null) {
            return;
        }

        try {
            String indexKey = documentIndexKey(userId, documentId);
            var keys = redisTemplate.opsForSet().members(indexKey);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            redisTemplate.delete(indexKey);
        } catch (Exception ex) {
            log.warn("RAG retrieval cache eviction skipped. documentId={}, reason={}", documentId, ex.getMessage());
        }
    }

    private String cacheKey(Long userId, Long documentId, String question) {
        return keyPrefix + ":user:" + userId + ":doc:" + documentId + ":q:" + sha256(normalizeQuestion(question));
    }

    private String documentIndexKey(Long userId, Long documentId) {
        return keyPrefix + ":index:user:" + userId + ":doc:" + documentId;
    }

    private String normalizeQuestion(String question) {
        return question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record RetrievalCacheEntry(
            Long userId,
            Long documentId,
            String normalizedQuestion,
            List<Long> chunkIds,
            List<CachedChunk> chunks,
            double confidenceScore,
            String confidenceLevel,
            String confidenceReason,
            long createdAt
    ) {
    }

    public record CachedChunk(
            Long id,
            String citationId,
            String sourceType,
            double score,
            double confidence,
            Double vectorScore,
            Double bm25Score,
            Integer vectorRank,
            Integer bm25Rank
    ) {
    }
}
