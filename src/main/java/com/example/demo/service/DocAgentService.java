package com.example.demo.service;

import com.example.demo.repository.DocumentChunkProjection;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocAgentService {

    public String normalizeTask(String task) {
        if (task == null) {
            return "summary";
        }
        String value = task.trim().toLowerCase();
        return switch (value) {
            case "summary", "outline", "risk", "qa" -> value;
            default -> "summary";
        };
    }

    public String defaultQuestionForTask(String task) {
        String normalized = normalizeTask(task);
        return switch (normalized) {
            case "outline" -> "请为该文档生成可执行的结构化提纲。";
            case "risk" -> "请识别该文档中的主要风险、歧义点和缺失信息。";
            case "qa" -> "请基于文档内容回答问题。";
            default -> "请为该文档生成结构化摘要。";
        };
    }

    public String buildAgentPrompt(String task, String question, List<DocumentChunkProjection> chunks) {
        String normalizedTask = normalizeTask(task);
        String safeQuestion = (question == null || question.isBlank()) ? defaultQuestionForTask(normalizedTask) : question;
        String taskInstruction = taskInstruction(normalizedTask);
        String context = buildContext(chunks);

        return """
                你是“文档任务Agent”。
                请严格基于检索到的上下文完成任务，不要编造信息。
                如果上下文不足，请明确指出缺失信息。
                你必须始终使用简体中文输出。

                任务类型：
                %s

                任务指令：
                %s

                检索上下文：
                %s

                用户请求：
                %s

                输出要求：
                1. 使用简洁、结构化的 Markdown。
                2. 先给结论，再给细节。
                3. 最后增加“证据引用”小节，引用 2-5 条关键依据。
                """.formatted(normalizedTask, taskInstruction, context, safeQuestion);
    }

    private String buildContext(List<DocumentChunkProjection> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "(No retrieved context)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkProjection chunk = chunks.get(i);
            builder.append("[Chunk ").append(i + 1).append("]\n");
            builder.append(chunk.getText() == null ? "" : chunk.getText()).append("\n\n");
        }
        return builder.toString();
    }

    private String taskInstruction(String task) {
        return switch (task) {
            case "outline" -> "生成清晰提纲，包含章节和关键要点，可直接用于写作或评审。";
            case "risk" -> "识别风险、不一致项、缺失假设和表述不清处，并给出具体优化建议。";
            case "qa" -> "先直接回答用户问题，再补充支撑细节。";
            default -> "生成结构化摘要，包含：背景、核心内容、方法机制、预期价值。";
        };
    }

    public String taskDisplayName(String task) {
        return switch (normalizeTask(task)) {
            case "outline" -> "提纲";
            case "risk" -> "风险审阅";
            case "qa" -> "问答";
            default -> "摘要";
        };
    }
}
