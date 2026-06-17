package com.example.demo.service;

import com.example.demo.entity.ChunkContentType;

public record ParsedChunk(
        ChunkContentType contentType,
        Integer pageNumber,
        String sectionTitle,
        String text,
        String sourcePath,
        String metadata
) {
}
