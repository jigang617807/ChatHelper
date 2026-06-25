package com.example.agent.service;

import com.example.agent.entity.AgentToolConfig;
import com.example.agent.repository.AgentToolConfigRepository;
import com.example.agent.tool.AgentToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentToolManagementService {

    private final AgentToolRegistry toolRegistry;
    private final AgentToolConfigRepository toolConfigRepository;

    @Transactional
    public List<AgentToolConfig> listTools() {
        toolRegistry.syncToolConfigs();
        return toolConfigRepository.findAllByOrderByToolSourceAscToolNameAsc();
    }

    @Transactional
    public AgentToolConfig setToolEnabled(String toolName, boolean enabled) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        toolRegistry.syncToolConfigs();
        AgentToolConfig config = toolConfigRepository.findByToolName(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Tool is not registered: " + toolName));
        config.setEnabled(enabled);
        config.setUpdatedAt(LocalDateTime.now());
        return toolConfigRepository.save(config);
    }
}
