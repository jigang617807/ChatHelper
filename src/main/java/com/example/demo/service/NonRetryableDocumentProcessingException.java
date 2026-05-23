package com.example.demo.service;

public class NonRetryableDocumentProcessingException extends DocumentProcessingException {

    public NonRetryableDocumentProcessingException(String message) {
        super(message);
    }

    public NonRetryableDocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
