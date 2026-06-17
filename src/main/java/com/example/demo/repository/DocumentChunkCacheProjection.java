package com.example.demo.repository;

public interface DocumentChunkCacheProjection extends DocumentChunkProjection {
    Long getId();

    @Override
    Integer getChunkIndex();

    @Override
    Integer getPageNumber();

    @Override
    String getSectionTitle();

    @Override
    String getContentType();

    @Override
    String getSourcePath();
}
