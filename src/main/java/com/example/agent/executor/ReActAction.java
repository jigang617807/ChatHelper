package com.example.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public record ReActAction(
        String type,
        String plan,
        String toolName,
        Map<String, Object> arguments,
        String finalAnswer
) {

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
            return new ReActAction("finish", "Model returned non-JSON output.", null, Map.of(), text);
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
}
