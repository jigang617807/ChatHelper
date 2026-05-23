package com.example.demo.service;

public class RetryableDocumentProcessingException extends DocumentProcessingException {

    public RetryableDocumentProcessingException(String message) {
        super(message);
    }

    public RetryableDocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
