package com.example.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentDeepSeekConfig {

    @Bean("agentDeepSeekChatModel")
    public ChatModel agentDeepSeekChatModel(
            @Value("${agent.deepseek.api-key}") String apiKey,
            @Value("${agent.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.deepseek.model:deepseek-chat}") String model,
            @Value("${agent.deepseek.temperature:0.4}") Double temperature,
            @Value("${agent.deepseek.max-tokens:2048}") Integer maxTokens) {

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .build();
    }
}
