package com.example.agent.tool.react;

import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.repository.DocumentChunkProjection;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.RagCachedRetrievalService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class RagSearchReactTool implements ReactTool {

    private final DocumentRepository documentRepository;
    private final RagCachedRetrievalService ragCachedRetrievalService;

    public RagSearchReactTool(DocumentRepository documentRepository,
                              RagCachedRetrievalService ragCachedRetrievalService) {
        this.documentRepository = documentRepository;
        this.ragCachedRetrievalService = ragCachedRetrievalService;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public String description() {
        return "Search relevant chunks from uploaded documents. Use document_list first when the target document is unclear. documentId is required for a specific document; omit it only for explicit cross-document search.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "question": "string, required",
                  "documentId": "number, optional; required for a specific document",
                  "topK": "number, optional, 1-10"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String question = ToolArguments.string(arguments, "question");
        if (question == null || question.isBlank()) {
            return ToolExecutionResult.failure("question is required.");
        }
        Long documentId = longValue(arguments == null ? null : arguments.get("documentId"));
        int topK = ToolArguments.integer(arguments, "topK", 5, 1, 10);
        if (documentId != null) {
            return searchOne(context.userId(), documentId, question, topK);
        }

        List<Document> documents = documentRepository.findByUserId(context.userId()).stream()
                .filter(document -> document.getStatus() == DocStatus.COMPLETED)
                .toList();
        if (documents.isEmpty()) {
            return ToolExecutionResult.success("No completed documents are available for RAG search.");
        }

        StringBuilder result = new StringBuilder();
        for (Document document : documents) {
            ToolExecutionResult part = searchOne(context.userId(), document.getId(), question, Math.min(topK, 3));
            if (part.success() && part.content() != null && !part.content().isBlank()) {
                result.append("## ").append(document.getTitle()).append(" (id=").append(document.getId()).append(")\n")
                        .append(part.content()).append("\n\n");
            }
            if (result.length() > 10000) {
                break;
            }
        }
        return result.isEmpty()
                ? ToolExecutionResult.success("No relevant chunks were found.")
                : ToolExecutionResult.success(result.toString());
    }

    private ToolExecutionResult searchOne(Long userId, Long documentId, String question, int topK) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId).orElse(null);
        if (document == null) {
            return ToolExecutionResult.failure("Document " + documentId + " does not exist or is not owned by the current user.");
        }
        if (document.getStatus() != DocStatus.COMPLETED) {
            return ToolExecutionResult.failure("Document " + documentId + " is not ready for RAG search. Current status: " + document.getStatus());
        }

        List<DocumentChunkProjection> chunks = ragCachedRetrievalService.searchRelevant(userId, documentId, question);
        if (chunks == null || chunks.isEmpty()) {
            return ToolExecutionResult.success("No relevant chunks were found in document " + documentId + ".");
        }
        String result = chunks.stream()
                .filter(Objects::nonNull)
                .map(DocumentChunkProjection::getText)
                .filter(text -> text != null && !text.isBlank())
                .limit(topK)
                .collect(Collectors.joining("\n\n---\n\n"));
        return ToolExecutionResult.success(result);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
