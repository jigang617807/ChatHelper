package com.example.agent.tool;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentStepService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentStepService stepService;
    private final AgentToolSource source;

    public LoggingToolCallback(ToolCallback delegate, AgentStepService stepService, AgentToolSource source) {
        this.delegate = delegate;
        this.stepService = stepService;
        this.source = source;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    public AgentToolSource getSource() {
        return source;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long sessionId = sessionId(toolContext);
        String toolName = getToolDefinition().name();
        long startedAt = System.currentTimeMillis();
        if (sessionId != null) {
            stepService.recordToolCall(sessionId, toolName, source, toolInput);
        }
        try {
            String result = delegate.call(toolInput, toolContext);
            if (sessionId != null) {
                stepService.recordToolResult(sessionId, toolName, source, toolInput, result,
                        System.currentTimeMillis() - startedAt);
            }
            return result;
        } catch (RuntimeException ex) {
            if (sessionId != null) {
                stepService.recordToolError(sessionId, toolName, source, toolInput, ex.getMessage(),
                        System.currentTimeMillis() - startedAt);
            }
            throw ex;
        }
    }

    private Long sessionId(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get("sessionId");
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
