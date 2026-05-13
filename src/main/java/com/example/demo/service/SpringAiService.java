package com.example.demo.service;

import com.example.demo.entity.ChatMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpringAiService {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public SpringAiService(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                           EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public List<Double> embedding(String text) {
        float[] vector = embeddingModel.embed(text);
        List<Double> result = new ArrayList<>(vector.length);
        for (float value : vector) {
            result.add((double) value);
        }
        return result;
    }

    public Flux<String> streamAnswer(List<ChatMessage> history, String promptText) {
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            for (ChatMessage message : history) {
                Message converted = toSpringAiMessage(message.getRole(), message.getMessage());
                if (converted != null) {
                    messages.add(converted);
                }
            }
        }
        messages.add(new UserMessage(promptText));

        return chatModel.stream(new Prompt(messages))
                .map(this::extractText)
                .filter(chunk -> chunk != null && !chunk.isBlank());
    }

    private Message toSpringAiMessage(String role, String content) {
        String safeContent = content == null ? "" : content;
        if ("assistant".equalsIgnoreCase(role)) {
            return new AssistantMessage(safeContent);
        }
        if ("system".equalsIgnoreCase(role)) {
            return new SystemMessage(safeContent);
        }
        if ("user".equalsIgnoreCase(role)) {
            return new UserMessage(safeContent);
        }
        return null;
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
