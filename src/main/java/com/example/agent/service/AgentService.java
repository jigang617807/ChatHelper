package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import com.example.agent.tool.AgentToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {

    private final ChatModel chatModel;
    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final AgentToolRegistry toolRegistry;

    public AgentService(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                        AgentSessionService sessionService,
                        AgentStepService stepService,
                        AgentToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.sessionService = sessionService;
        this.stepService = stepService;
        this.toolRegistry = toolRegistry;
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question) {
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            return Flux.just("Please enter a question for the AI agent.", "[DONE]");
        }

        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        AgentMessage userMessage = sessionService.saveMessage(session.getId(), "user", safeQuestion);
        stepService.recordPlan(session.getId(), userMessage.getId(),
                "Analyze the task, choose tools when needed, then produce the final answer.");

        List<Message> history = toSpringMessages(sessionService.listMessages(session.getId()), userMessage.getId());
        ChatClient chatClient = ChatClient.create(chatModel);
        StringBuilder finalAnswer = new StringBuilder();

        return chatClient.prompt()
                .system(systemPrompt())
                .messages(history)
                .user(safeQuestion)
                .toolCallbacks(toolRegistry.loggingCallbacks())
                .toolContext(Map.of(
                        "userId", userId,
                        "sessionId", session.getId(),
                        "messageId", userMessage.getId()
                ))
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .doOnNext(finalAnswer::append)
                .doOnComplete(() -> {
                    sessionService.saveMessage(session.getId(), "assistant", finalAnswer.toString());
                    stepService.recordFinal(session.getId(), userMessage.getId(), finalAnswer.toString());
                })
                .doOnError(error -> stepService.recordError(session.getId(), userMessage.getId(), error.getMessage()))
                .concatWithValues("[DONE]");
    }

    private List<Message> toSpringMessages(List<AgentMessage> messages, Long currentUserMessageId) {
        List<Message> result = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message.getId() != null && message.getId().equals(currentUserMessageId)) {
                continue;
            }
            String content = message.getContent() == null ? "" : message.getContent();
            if ("assistant".equalsIgnoreCase(message.getRole())) {
                result.add(new AssistantMessage(content));
            } else if ("user".equalsIgnoreCase(message.getRole())) {
                result.add(new UserMessage(content));
            }
        }
        return result;
    }

    private String systemPrompt() {
        return """
                You are the AI Super Agent module of this system.

                Rules:
                1. If the task needs knowledge from uploaded documents, call document_list and rag_search first.
                2. If the task needs calculation, current time, or resume-style project wording, use the matching local tool.
                3. If MCP tools are registered by Spring AI, choose them according to their tool descriptions.
                4. Do not invent document content. Ground document-related answers in tool results.
                5. Answer in Chinese with clear Markdown structure.
                """;
    }
}
