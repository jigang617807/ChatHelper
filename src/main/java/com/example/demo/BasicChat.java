package com.example.demo;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;

import java.util.List;

/**
 * Simple SDK smoke test for Zhipu direct API usage.
 * Main business flow now uses Spring AI; this class is only a standalone demo.
 */
public class BasicChat {

    public static void main(String[] args) {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Missing env: ZHIPU_API_KEY");
            return;
        }

        ZhipuAiClient client = ZhipuAiClient.builder()
                .apiKey(apiKey)
                .ofZHIPU()
                .build();

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model("glm-4.7-flash")
                .messages(List.of(
                        ChatMessage.builder()
                                .role(ChatMessageRole.USER.value())
                                .content("你好，做个50字内自我介绍。")
                                .build()
                ))
                .build();

        ChatCompletionResponse response = client.chat().createChatCompletion(request);
        if (!response.isSuccess()) {
            System.err.println("Request failed: " + response.getMsg());
            return;
        }

        String content = String.valueOf(
                response.getData().getChoices().get(0).getMessage().getContent()
        );
        System.out.println(content);
    }
}
