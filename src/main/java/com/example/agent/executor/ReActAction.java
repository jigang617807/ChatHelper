package com.example.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ReActAction(
        String type,
        String plan,
        String toolName,
        Map<String, Object> arguments,
        String finalAnswer
) {

    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "(?s)\"finalAnswer\"\\s*:\\s*\"(.*)\"\\s*[,}]\\s*$");

    public boolean isFinish() {
        return "finish".equalsIgnoreCase(type);
    }

    public boolean isTool() {
        return "tool".equalsIgnoreCase(type);
    }

    public static ReActAction fromModelText(String text, ObjectMapper objectMapper) {
        try {
            String json = extractJson(text);
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("type").asText("");
            String plan = node.path("plan").asText("");
            String toolName = node.path("toolName").asText("");
            String finalAnswer = node.path("finalAnswer").asText("");
            Map<String, Object> arguments = new LinkedHashMap<>();
            JsonNode argsNode = node.path("arguments");
            if (argsNode.isObject()) {
                arguments = objectMapper.convertValue(argsNode, objectMapper.getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, Object.class));
            }
            return new ReActAction(type, plan, toolName, arguments, finalAnswer);
        } catch (Exception ex) {
            String finalAnswer = extractFinalAnswerFallback(text);
            if (finalAnswer != null && !finalAnswer.isBlank()) {
                return new ReActAction("finish",
                        "Recovered finalAnswer from malformed JSON output.", null, Map.of(), finalAnswer);
            }
            String safeAnswer = looksLikeJson(text)
                    ? "The model returned malformed ReAct JSON, and the final answer could not be parsed. Please try again."
                    : text;
            return new ReActAction("finish", "Model returned non-JSON output.", null, Map.of(), safeAnswer);
        }
    }

    private static String extractJson(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("(?s)^```(?:json)?\\s*", "");
            value = value.replaceFirst("(?s)\\s*```$", "");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private static String extractFinalAnswerFallback(String text) {
        String value = extractJson(text);
        Matcher matcher = FINAL_ANSWER_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJsonLike(matcher.group(1));
    }

    private static String unescapeJsonLike(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }

    private static boolean looksLikeJson(String text) {
        String value = text == null ? "" : text.trim();
        return value.startsWith("{") || value.startsWith("```json") || value.startsWith("```");
    }
}
