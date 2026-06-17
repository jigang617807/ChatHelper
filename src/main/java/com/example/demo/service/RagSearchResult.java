package com.example.demo.service;

import java.util.List;
import java.util.Locale;

public class RagSearchResult {

    private final String question;
    private final List<RagChunkEvidence> evidence;
    private final double confidenceScore;
    private final String confidenceLevel;
    private final String confidenceReason;

    public RagSearchResult(String question,
                           List<RagChunkEvidence> evidence,
                           double confidenceScore,
                           String confidenceLevel,
                           String confidenceReason) {
        this.question = question;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.confidenceScore = confidenceScore;
        this.confidenceLevel = confidenceLevel;
        this.confidenceReason = confidenceReason;
    }

    public String getQuestion() {
        return question;
    }

    public List<RagChunkEvidence> getEvidence() {
        return evidence;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public String getConfidenceReason() {
        return confidenceReason;
    }

    public boolean isEmpty() {
        return evidence.isEmpty();
    }

    public String buildPromptContext() {
        if (evidence.isEmpty()) {
            return "(No retrieved context)";
        }
        StringBuilder builder = new StringBuilder();
        for (RagChunkEvidence item : evidence) {
            builder.append("[")
                    .append(item.getCitationId())
                    .append("] chunkId=")
                    .append(item.getId())
                    .append(", chunkIndex=")
                    .append(item.getChunkIndex() == null ? "unknown" : item.getChunkIndex())
                    .append(", source=")
                    .append(item.getSourceType())
                    .append(", relevance=")
                    .append(format(item.getConfidence()))
                    .append("\n")
                    .append(item.getText() == null ? "" : item.getText())
                    .append("\n\n");
        }
        return builder.toString();
    }

    public String buildCitationSummary() {
        if (evidence.isEmpty()) {
            return "\n\n**答案置信度**\n- 置信度：低 (0.00)\n- 说明：没有检索到可用证据。\n";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n**答案置信度**\n")
                .append("- 置信度：")
                .append(confidenceLevel)
                .append(" (")
                .append(format(confidenceScore))
                .append(")\n")
                .append("- 说明：")
                .append(confidenceReason)
                .append("\n\n")
                .append("**引用来源**\n");
        for (RagChunkEvidence item : evidence) {
            builder.append("- [")
                    .append(item.getCitationId())
                    .append("] chunkId=")
                    .append(item.getId())
                    .append(", chunkIndex=")
                    .append(item.getChunkIndex() == null ? "unknown" : item.getChunkIndex())
                    .append(", source=")
                    .append(item.getSourceType())
                    .append(", relevance=")
                    .append(format(item.getConfidence()))
                    .append("\n");
        }
        return builder.toString();
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
