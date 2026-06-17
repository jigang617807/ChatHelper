package com.example.agent.tool;

import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.RagCachedRetrievalService;
import com.example.demo.service.RagChunkEvidence;
import com.example.demo.service.RagSearchResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class AgentRagTools {

    private final DocumentRepository documentRepository;
    private final ObjectProvider<RagCachedRetrievalService> ragCachedRetrievalServiceProvider;

    public AgentRagTools(DocumentRepository documentRepository,
                         ObjectProvider<RagCachedRetrievalService> ragCachedRetrievalServiceProvider) {
        this.documentRepository = documentRepository;
        this.ragCachedRetrievalServiceProvider = ragCachedRetrievalServiceProvider;
    }

    @Tool(name = "document_list", description = "List the current user's uploaded documents that can be used by RAG.")
    public String documentList(ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        if (userId == null) {
            return "No user context is available.";
        }
        List<Document> documents = documentRepository.findByUserId(userId);
        if (documents.isEmpty()) {
            return "The user has not uploaded any documents.";
        }
        return documents.stream()
                .map(document -> "id=" + document.getId()
                        + ", title=" + document.getTitle()
                        + ", status=" + document.getStatus())
                .collect(Collectors.joining("\n"));
    }

    @Tool(name = "rag_search", description = "Search relevant chunks from the user's uploaded documents using the existing RAG module.")
    public String ragSearch(
            @ToolParam(description = "Question or keywords to search in the document knowledge base.") String question,
            @ToolParam(description = "Optional document id. If omitted, search all completed documents owned by the current user.", required = false) Long documentId,
            @ToolParam(description = "Optional max number of text chunks to return.", required = false) Integer topK,
            ToolContext toolContext) {

        Long userId = currentUserId(toolContext);
        if (userId == null) {
            return "No user context is available.";
        }
        if (question == null || question.isBlank()) {
            return "Question is required for RAG search.";
        }

        int limit = topK == null ? 5 : Math.max(1, Math.min(topK, 10));
        if (documentId != null) {
            return searchOneDocument(userId, documentId, question, limit);
        }

        List<Document> documents = documentRepository.findByUserId(userId).stream()
                .filter(document -> document.getStatus() == DocStatus.COMPLETED)
                .toList();
        if (documents.isEmpty()) {
            return "No completed documents are available for RAG search.";
        }

        StringBuilder result = new StringBuilder();
        for (Document document : documents) {
            String part = searchOneDocument(userId, document.getId(), question, Math.min(limit, 3));
            if (!part.isBlank()) {
                result.append("## ").append(document.getTitle()).append(" (id=").append(document.getId()).append(")\n")
                        .append(part).append("\n\n");
            }
            if (result.length() > 8000) {
                break;
            }
        }
        return result.isEmpty() ? "No relevant chunks were found." : result.toString();
    }

    private String searchOneDocument(Long userId, Long documentId, String question, int limit) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId).orElse(null);
        if (document == null) {
            return "Document " + documentId + " does not exist or is not owned by the current user.";
        }
        if (document.getStatus() != DocStatus.COMPLETED) {
            return "Document " + documentId + " is not ready for RAG search. Current status: " + document.getStatus();
        }

        RagSearchResult searchResult =
                ragCachedRetrievalServiceProvider.getObject().search(userId, documentId, question);
        if (searchResult.isEmpty()) {
            return "No relevant chunks were found in document " + documentId + ".";
        }
        String evidence = searchResult.getEvidence().stream()
                .limit(limit)
                .map(item -> formatEvidence(document, item))
                .collect(Collectors.joining("\n\n---\n\n"));
        return evidence + searchResult.buildCitationSummary();
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

    private Long currentUserId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get("userId");
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
