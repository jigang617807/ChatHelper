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
        return "End the task when enough information has been gathered. Do not put the final user-facing answer in this tool; the answer synthesizer will write it.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "reason": "string, optional"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String reason = ToolArguments.string(arguments, "reason");
        return ToolExecutionResult.success(reason == null || reason.isBlank()
                ? "Task termination requested."
                : "Task termination requested. Reason: " + reason);
    }
}
