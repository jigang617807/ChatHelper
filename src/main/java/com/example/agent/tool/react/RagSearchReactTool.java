package com.example.agent.tool.react;

import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.RagCachedRetrievalService;
import com.example.demo.service.RagChunkEvidence;
import com.example.demo.service.RagSearchResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return "Search relevant chunks from uploaded documents with citation ids, lightweight rerank scores and retrieval confidence.";
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

        RagSearchResult searchResult = ragCachedRetrievalService.search(userId, documentId, question);
        if (searchResult.isEmpty()) {
            return ToolExecutionResult.success("No relevant chunks were found in document " + documentId + ".");
        }
        String result = searchResult.getEvidence().stream()
                .limit(topK)
                .map(evidence -> formatEvidence(document, evidence))
                .collect(Collectors.joining("\n\n---\n\n"));
        return ToolExecutionResult.success(result + searchResult.buildCitationSummary());
    }

    private String formatEvidence(Document document, RagChunkEvidence evidence) {
        return "[" + evidence.getCitationId() + "] "
                + "documentId=" + document.getId()
                + ", documentTitle=" + document.getTitle()
                + ", chunkId=" + evidence.getId()
                + ", chunkIndex=" + evidence.getChunkIndex()
                + ", page=" + evidence.getPageNumber()
                + ", type=" + evidence.getContentType()
                + ", section=" + blankToDefault(evidence.getSectionTitle(), "-")
                + ", source=" + evidence.getSourceType()
                + ", relevance=" + String.format(Locale.ROOT, "%.2f", evidence.getConfidence())
                + sourcePathLine(evidence)
                + "\n"
                + (evidence.getText() == null ? "" : evidence.getText());
    }

    private String sourcePathLine(RagChunkEvidence evidence) {
        return evidence.getSourcePath() == null || evidence.getSourcePath().isBlank()
                ? ""
                : ", sourcePath=" + evidence.getSourcePath();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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
