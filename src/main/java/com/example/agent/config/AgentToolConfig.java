package com.example.agent.config;

import com.example.agent.tool.AgentBasicTools;
import com.example.agent.tool.AgentRagTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentToolConfig {

    @Bean
    public ToolCallbackProvider agentLocalToolCallbackProvider(AgentRagTools ragTools, AgentBasicTools basicTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ragTools, basicTools)
                .build();
    }
}
