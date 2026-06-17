package com.example.demo.repository;

public interface DocumentChunkProjection {
    String getText();

    default Integer getChunkIndex() {
        return null;
    }

    default Integer getPageNumber() {
        return null;
    }

    default String getSectionTitle() {
        return null;
    }

    default String getContentType() {
        return null;
    }

    default String getSourcePath() {
        return null;
    }
}
