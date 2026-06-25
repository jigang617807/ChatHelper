package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolConfig;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.repository.AgentToolConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ReactToolRegistry {

    private final Map<String, ReactTool> tools;
    private final AgentToolConfigRepository toolConfigRepository;

    public ReactToolRegistry(List<ReactTool> reactTools,
                             @Qualifier("agentLocalToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
                             ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                             AgentToolConfigRepository toolConfigRepository,
                             ObjectMapper objectMapper) {
        this.toolConfigRepository = toolConfigRepository;
        Map<String, ReactTool> discovered = new LinkedHashMap<>();
        for (ReactTool tool : reactTools) {
            discovered.putIfAbsent(tool.name(), tool);
        }

        Set<String> localToolNames = Arrays.stream(localToolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        toolCallbackProviders.forEach(provider -> {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                AgentToolSource source = localToolNames.contains(callback.getToolDefinition().name())
                        ? AgentToolSource.LOCAL
                        : AgentToolSource.MCP;
                discovered.putIfAbsent(callback.getToolDefinition().name(),
                        new ToolCallbackReactTool(callback, source, objectMapper));
            }
        });
        this.tools = Map.copyOf(discovered);
    }

    public Optional<ReactTool> find(String name) {
        return find(name, Set.of());
    }

    public Optional<ReactTool> find(String name, Set<String> allowedToolNames) {
        ReactTool tool = tools.get(name);
        if (tool == null || !isAllowedBySkill(name, allowedToolNames) || !isEnabled(name)) {
            return Optional.empty();
        }
        return Optional.of(tool);
    }

    public List<ReactTool> list() {
        return list(Set.of());
    }

    public List<ReactTool> list(Set<String> allowedToolNames) {
        return tools.values().stream()
                .filter(tool -> isAllowedBySkill(tool.name(), allowedToolNames))
                .filter(tool -> isEnabled(tool.name()))
                .toList();
    }

    public String toolDescriptions() {
        return toolDescriptions(Set.of());
    }

    public String toolDescriptions(Set<String> allowedToolNames) {
        return list(allowedToolNames).stream()
                .map(tool -> """
                        - name: %s
                          source: %s
                          description: %s
                          parameters: %s
                        """.formatted(tool.name(), tool.source(), tool.description(), tool.parameters()))
                .collect(Collectors.joining("\n"));
    }

    private boolean isAllowedBySkill(String toolName, Set<String> allowedToolNames) {
        return allowedToolNames == null || allowedToolNames.isEmpty() || allowedToolNames.contains(toolName);
    }

    private boolean isEnabled(String toolName) {
        return toolConfigRepository.findByToolName(toolName)
                .map(AgentToolConfig::getEnabled)
                .orElse(true);
    }
}
