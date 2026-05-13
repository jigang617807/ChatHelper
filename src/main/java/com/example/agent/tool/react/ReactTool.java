package com.example.agent.tool.react;

import java.util.Map;

public interface ReactTool {

    String name();

    String description();

    String parameters();

    ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}
