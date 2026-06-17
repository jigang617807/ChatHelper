package com.example.demo.service;

import com.example.demo.repository.DocumentChunkCacheProjection;

public class RagChunkEvidence implements DocumentChunkCacheProjection {

    private final Long id;
    private final String text;
    private final Integer chunkIndex;
    private final String citationId;
    private final String sourceType;
    private final double score;
    private final double confidence;
    private final Double vectorScore;
    private final Double bm25Score;
    private final Integer vectorRank;
    private final Integer bm25Rank;

    public RagChunkEvidence(Long id,
                            String text,
                            Integer chunkIndex,
                            String citationId,
                            String sourceType,
                            double score,
                            double confidence,
                            Double vectorScore,
                            Double bm25Score,
                            Integer vectorRank,
                            Integer bm25Rank) {
        this.id = id;
        this.text = text;
        this.chunkIndex = chunkIndex;
        this.citationId = citationId;
        this.sourceType = sourceType;
        this.score = score;
        this.confidence = confidence;
        this.vectorScore = vectorScore;
        this.bm25Score = bm25Score;
        this.vectorRank = vectorRank;
        this.bm25Rank = bm25Rank;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getCitationId() {
        return citationId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public double getScore() {
        return score;
    }

    public double getConfidence() {
        return confidence;
    }

    public Double getVectorScore() {
        return vectorScore;
    }

    public Double getBm25Score() {
        return bm25Score;
    }

    public Integer getVectorRank() {
        return vectorRank;
    }

    public Integer getBm25Rank() {
        return bm25Rank;
    }
}
