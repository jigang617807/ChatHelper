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
import java.util.Comparator;
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

    @Value("${rag.cache.key-prefix:rag:retrieval:v1}")
    private String keyPrefix;

    public Optional<List<DocumentChunkCacheProjection>> get(Long userId, Long documentId, String question) {
        if (!enabled || userId == null || documentId == null || question == null || question.isBlank()) {
            return Optional.empty();
        }

        try {
            String json = redisTemplate.opsForValue().get(cacheKey(userId, documentId, question));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }

            RetrievalCacheEntry entry = objectMapper.readValue(json, RetrievalCacheEntry.class);
            if (entry.chunkIds() == null || entry.chunkIds().isEmpty()) {
                return Optional.empty();
            }

            List<DocumentChunkCacheProjection> chunks =
                    chunkRepository.findByDocumentIdAndIdIn(documentId, entry.chunkIds());
            if (chunks.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(orderByCachedIds(chunks, entry.chunkIds()));
        } catch (Exception ex) {
            log.warn("RAG retrieval cache read skipped. documentId={}, reason={}", documentId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long userId,
                    Long documentId,
                    String question,
                    List<? extends DocumentChunkCacheProjection> chunks) {
        if (!enabled || userId == null || documentId == null || question == null || question.isBlank()
                || chunks == null || chunks.isEmpty()) {
            return;
        }

        List<Long> chunkIds = chunks.stream()
                .map(DocumentChunkCacheProjection::getId)
                .filter(id -> id != null)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (chunkIds.isEmpty()) {
            return;
        }

        RetrievalCacheEntry entry = new RetrievalCacheEntry(
                userId,
                documentId,
                normalizeQuestion(question),
                chunkIds,
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

    private List<DocumentChunkCacheProjection> orderByCachedIds(List<DocumentChunkCacheProjection> chunks,
                                                                 List<Long> cachedIds) {
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < cachedIds.size(); i++) {
            order.putIfAbsent(cachedIds.get(i), i);
        }
        return chunks.stream()
                .filter(chunk -> chunk.getId() != null)
                .sorted(Comparator.comparingInt(chunk -> order.getOrDefault(chunk.getId(), Integer.MAX_VALUE)))
                .toList();
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
            long createdAt
    ) {
    }
}
