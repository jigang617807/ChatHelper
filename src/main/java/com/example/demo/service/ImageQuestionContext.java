package com.example.demo.service;

public record ImageQuestionContext(
        String id,
        Long userId,
        String originalName,
        String contentType,
        long size,
        Integer width,
        Integer height,
        String filePath,
        String webPath,
        String description
) {
}
