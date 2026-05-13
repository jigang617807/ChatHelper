package com.example.agent.tool.react;

import java.util.Map;

final class ToolArguments {

    private ToolArguments() {
    }

    static String string(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : String.valueOf(value);
    }

    static int integer(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object value = arguments == null ? null : arguments.get(name);
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }
}
