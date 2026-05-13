package com.example.demo.service;

import com.example.demo.repository.DocumentChunkCacheProjection;
import com.example.demo.repository.DocumentChunkProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagCachedRetrievalService {

    private final RagService ragService;
    private final RagRetrievalCacheService cacheService;

    public List<DocumentChunkProjection> searchRelevant(Long userId, Long documentId, String question) {
        var cachedChunks = cacheService.get(userId, documentId, question);
        if (cachedChunks.isPresent()) {
            return new ArrayList<>(cachedChunks.get());
        }
        return searchAndCache(userId, documentId, question);
    }

    private List<DocumentChunkProjection> searchAndCache(Long userId, Long documentId, String question) {
        List<DocumentChunkCacheProjection> chunks = ragService.searchRelevantWithIds(documentId, question);
        cacheService.put(userId, documentId, question, chunks);
        return new ArrayList<>(chunks);
    }
}
