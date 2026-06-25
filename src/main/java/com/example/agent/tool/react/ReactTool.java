package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolSource;

import java.util.Map;

public interface ReactTool {

    String name();

    String description();

    String parameters();

    ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments);

    default AgentToolSource source() {
        return AgentToolSource.LOCAL;
    }
}
