package com.example.agent.tool;

import com.example.agent.entity.AgentToolConfig;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.repository.AgentToolConfigRepository;
import com.example.agent.service.AgentStepService;
import com.example.agent.tool.react.ReactTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {

    private final ToolCallbackProvider localToolCallbackProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ObjectProvider<ReactTool> reactTools;
    private final AgentToolConfigRepository toolConfigRepository;
    private final AgentStepService stepService;

    public AgentToolRegistry(@Qualifier("agentLocalToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
                             ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                             ObjectProvider<ReactTool> reactTools,
                             AgentToolConfigRepository toolConfigRepository,
                             AgentStepService stepService) {
        this.localToolCallbackProvider = localToolCallbackProvider;
        this.toolCallbackProviders = toolCallbackProviders;
        this.reactTools = reactTools;
        this.toolConfigRepository = toolConfigRepository;
        this.stepService = stepService;
    }

    public List<ToolCallback> loggingCallbacks() {
        Set<String> localToolNames = Arrays.stream(localToolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        toolCallbackProviders.forEach(provider -> {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                callbacks.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        });

        List<ToolCallback> result = new ArrayList<>();
        callbacks.forEach((name, callback) -> {
            AgentToolSource source = localToolNames.contains(name) ? AgentToolSource.LOCAL : AgentToolSource.MCP;
            result.add(new LoggingToolCallback(callback, stepService, source));
        });
        return result;
    }

    @Transactional
    public void syncToolConfigs() {
        loggingCallbacks().forEach(callback -> {
            String name = callback.getToolDefinition().name();
            toolConfigRepository.findByToolName(name).orElseGet(() -> {
                AgentToolConfig config = new AgentToolConfig();
                config.setToolName(name);
                config.setToolSource(callback instanceof LoggingToolCallback loggingToolCallback
                        ? loggingToolCallback.getSource()
                        : AgentToolSource.MCP);
                config.setDescription(callback.getToolDefinition().description());
                config.setEnabled(true);
                return toolConfigRepository.save(config);
            });
        });
        reactTools.forEach(tool -> toolConfigRepository.findByToolName(tool.name()).orElseGet(() -> {
            AgentToolConfig config = new AgentToolConfig();
            config.setToolName(tool.name());
            config.setToolSource(AgentToolSource.LOCAL);
            config.setDescription(tool.description());
            config.setEnabled(true);
            return toolConfigRepository.save(config);
        }));
    }
}
