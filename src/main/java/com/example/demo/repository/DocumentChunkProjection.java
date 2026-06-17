package com.example.demo.repository;

public interface DocumentChunkProjection {
    String getText();

    default Integer getChunkIndex() {
        return null;
    }
}
