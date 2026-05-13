package com.example.agent.tool.react;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ReactToolRegistry {

    private final Map<String, ReactTool> tools;

    public ReactToolRegistry(List<ReactTool> tools) {
        this.tools = tools.stream()
                .collect(Collectors.toMap(ReactTool::name, tool -> tool, (first, ignored) -> first));
    }

    public Optional<ReactTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ReactTool> list() {
        return tools.values().stream().toList();
    }

    public String toolDescriptions() {
        return tools.values().stream()
                .map(tool -> """
                        - name: %s
                          description: %s
                          parameters: %s
                        """.formatted(tool.name(), tool.description(), tool.parameters()))
                .collect(Collectors.joining("\n"));
    }
}
