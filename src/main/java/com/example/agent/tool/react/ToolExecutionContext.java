package com.example.agent.tool.react;

import java.nio.file.Path;
import java.util.Set;

public record ToolExecutionContext(
        Long userId,
        Long sessionId,
        Long messageId,
        Path workspaceRoot,
        Long skillId,
        String skillName,
        Set<String> allowedToolNames
) {
    public ToolExecutionContext(Long userId, Long sessionId, Long messageId, Path workspaceRoot) {
        this(userId, sessionId, messageId, workspaceRoot, null, "General Agent", Set.of());
    }

    public boolean allowsTool(String toolName) {
        return allowedToolNames == null || allowedToolNames.isEmpty() || allowedToolNames.contains(toolName);
    }
}
