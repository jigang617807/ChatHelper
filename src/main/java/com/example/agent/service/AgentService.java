package com.example.agent.service;

import com.example.agent.executor.ReActAgentExecutor;
import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService {

    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final ReActAgentExecutor reactAgentExecutor;

    public AgentService(AgentSessionService sessionService,
                        AgentStepService stepService,
                        ReActAgentExecutor reactAgentExecutor) {
        this.sessionService = sessionService;
        this.stepService = stepService;
        this.reactAgentExecutor = reactAgentExecutor;
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question) {
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            return Flux.just("Please enter a question for the AI agent.", "[DONE]");
        }

        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        AgentMessage userMessage = sessionService.saveMessage(session.getId(), "user", safeQuestion);

        List<Message> history = toSpringMessages(sessionService.listMessages(session.getId()), userMessage.getId());
        return Mono.fromCallable(() -> reactAgentExecutor.execute(userId, session.getId(), userMessage.getId(), safeQuestion, history))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(answer -> {
                    sessionService.saveMessage(session.getId(), "assistant", answer);
                    return Flux.just(answer, "[DONE]");
                })
                .onErrorResume(error -> {
                    stepService.recordError(session.getId(), userMessage.getId(), error.getMessage());
                    return Flux.just("Agent execution failed: " + error.getMessage(), "[DONE]");
                });
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

}
