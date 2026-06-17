package com.example.demo.service;

import java.util.List;

public record ParsedDocument(
        String fullText,
        List<ParsedChunk> chunks
) {
}
