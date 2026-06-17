package com.example.demo.service;

import org.springframework.stereotype.Service;

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

    public String buildAgentPrompt(String task, String question, RagSearchResult searchResult) {
        String normalizedTask = normalizeTask(task);
        String safeQuestion = (question == null || question.isBlank()) ? defaultQuestionForTask(normalizedTask) : question;
        String taskInstruction = taskInstruction(normalizedTask);
        String context = searchResult == null ? "(No retrieved context)" : searchResult.buildPromptContext();

        return """
                你是“文档任务 Agent”。请严格基于检索证据完成任务，不要编造信息。
                如果上下文不足，请明确指出缺失信息。
                回答中的关键结论后请尽量标注引用编号，例如 [S1]、[S2]。
                你必须始终使用简体中文输出。

                任务类型：
                %s

                任务指令：
                %s

                检索证据：
                %s

                用户请求：
                %s

                输出要求：
                1. 使用简洁、结构化的 Markdown。
                2. 先给结论，再给细节。
                3. 证据不足时降低语气确定性，并说明需要补充的材料。
                """.formatted(normalizedTask, taskInstruction, context, safeQuestion);
    }

    private String taskInstruction(String task) {
        return switch (task) {
            case "outline" -> "生成清晰提纲，包含章节、关键要点和可执行后续动作。";
            case "risk" -> "识别风险、不一致项、缺失假设和表述不清处，并给出具体优化建议。";
            case "qa" -> "先直接回答用户问题，再补充支撑细节和证据编号。";
            default -> "生成结构化摘要，包含背景、核心内容、方法机制、结论或价值。";
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
