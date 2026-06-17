package com.example.demo.service;

public record OcrResult(
        boolean success,
        String text,
        Double confidence,
        String provider,
        String rawResponse,
        String errorMessage
) {
    public static OcrResult success(String text, Double confidence, String provider, String rawResponse) {
        return new OcrResult(true, text == null ? "" : text.strip(), confidence, provider, rawResponse, null);
    }

    public static OcrResult failure(String provider, String errorMessage) {
        return new OcrResult(false, "", null, provider, null, errorMessage);
    }
}
