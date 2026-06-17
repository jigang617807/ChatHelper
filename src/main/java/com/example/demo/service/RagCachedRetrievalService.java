package com.example.demo.service;

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

    public RagSearchResult search(Long userId, Long documentId, String question) {
        var cachedResult = cacheService.getResult(userId, documentId, question);
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }
        RagSearchResult result = ragService.search(documentId, question);
        cacheService.putResult(userId, documentId, question, result);
        return result;
    }

    public List<DocumentChunkProjection> searchRelevant(Long userId, Long documentId, String question) {
        return new ArrayList<>(search(userId, documentId, question).getEvidence());
    }

}
