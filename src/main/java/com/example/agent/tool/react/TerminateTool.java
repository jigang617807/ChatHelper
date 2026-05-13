package com.example.agent.tool.react;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TerminateTool implements ReactTool {

    @Override
    public String name() {
        return "terminate";
    }

    @Override
    public String description() {
        return "End the task when enough information has been gathered. Prefer using a finish action, but this tool is available as an explicit stop signal.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "finalAnswer": "string, required"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String finalAnswer = ToolArguments.string(arguments, "finalAnswer");
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return ToolExecutionResult.failure("finalAnswer is required.");
        }
        return ToolExecutionResult.success(finalAnswer);
    }
}
