package com.example.agent.tool.react;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class AgentWorkspaceService {

    private final Path root;

    public AgentWorkspaceService(@Value("${agent.react.workspace-root:uploads/agent-workspace}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path workspace(Long userId, Long sessionId) {
        Path workspace = root.resolve(String.valueOf(userId)).resolve(String.valueOf(sessionId)).normalize();
        if (!workspace.startsWith(root)) {
            throw new IllegalArgumentException("Invalid agent workspace path.");
        }
        try {
            Files.createDirectories(workspace);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create agent workspace: " + ex.getMessage(), ex);
        }
        return workspace;
    }

    public Path resolveInside(Path workspace, String fileName) {
        String safeName = sanitizeFileName(fileName);
        Path resolved = workspace.resolve(safeName).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("File path escapes the agent workspace.");
        }
        return resolved;
    }

    private String sanitizeFileName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        if (value.isBlank()) {
            value = "artifact";
        }
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return value.isBlank() ? "artifact" : value;
    }
}
