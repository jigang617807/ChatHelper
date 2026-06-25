package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import com.example.agent.executor.ReActAgentExecutor;
import com.example.agent.service.AgentSkillService.SkillProfile;
import com.example.demo.service.ImageQuestionContext;
import com.example.demo.service.ImageQuestionContextService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService {

    private static final int MIN_RECENT_MESSAGE_LIMIT = 4;
    private static final int MAX_RECENT_MESSAGE_LIMIT = 40;

    private final ChatModel chatModel;
    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final AgentSkillService skillService;
    private final ReActAgentExecutor reactAgentExecutor;
    private final ImageQuestionContextService imageQuestionContextService;
    private final int recentMessageLimit;
    private final int summaryMaxChars;

    public AgentService(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                        AgentSessionService sessionService,
                        AgentStepService stepService,
                        AgentSkillService skillService,
                        ReActAgentExecutor reactAgentExecutor,
                        ImageQuestionContextService imageQuestionContextService,
                        @Value("${agent.history.recent-message-limit:12}") int recentMessageLimit,
                        @Value("${agent.history.summary-max-chars:3000}") int summaryMaxChars) {
        this.chatModel = chatModel;
        this.sessionService = sessionService;
        this.stepService = stepService;
        this.skillService = skillService;
        this.reactAgentExecutor = reactAgentExecutor;
        this.imageQuestionContextService = imageQuestionContextService;
        this.recentMessageLimit = Math.max(MIN_RECENT_MESSAGE_LIMIT,
                Math.min(recentMessageLimit, MAX_RECENT_MESSAGE_LIMIT));
        this.summaryMaxChars = Math.max(500, summaryMaxChars);
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question) {
        return streamAsk(userId, sessionId, question, null);
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question, ImageQuestionContext imageContext) {
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            return Flux.just("Please enter a question for the AI agent.", "[DONE]");
        }
        String imagePromptContext = imageQuestionContextService.buildPromptContext(imageContext);
        String effectiveQuestion = imageContext == null
                ? safeQuestion
                : safeQuestion + "\n\n[图片输入]\n" + imageContext.description();

        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        SkillProfile skill = skillService.resolveSkill(session.getSkillId());
        AgentMessage userMessage = sessionService.saveMessage(session.getId(), "user",
                imageContext == null ? safeQuestion : safeQuestion + "\n\n![用户上传图片](" + imageContext.webPath() + ")");
        List<Message> history = buildHistoryWithSummary(session, userMessage.getId(), skill);

        if (!requiresReactTools(effectiveQuestion)) {
            return streamDirectAnswer(session, userMessage, effectiveQuestion, imagePromptContext, history, skill);
        }

        return Mono.fromCallable(() -> reactAgentExecutor.execute(
                        userId, session.getId(), userMessage.getId(), effectiveQuestion, history, skill))
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

    private Flux<String> streamDirectAnswer(AgentSession session,
                                            AgentMessage userMessage,
                                            String question,
                                            String imagePromptContext,
                                            List<Message> history,
                                            SkillProfile skill) {
        stepService.recordPlan(session.getId(), userMessage.getId(),
                "Direct streaming answer; no external tool is required.");
        StringBuilder finalAnswer = new StringBuilder();
        return ChatClient.create(chatModel)
                .prompt()
                .system("""
                        You are the AI assistant in this Agent workspace.
                        Active skill: %s (%s)
                        Skill instructions:
                        %s
                        Answer in Chinese with clear Markdown.
                        This request does not require local tools, web search, downloads, PDF generation, or RAG.
                        %s
                        Do not output ReAct JSON. Output only the final user-facing answer.
                        """.formatted(
                        skill.name(),
                        skill.code(),
                        blankToDefault(skill.systemPrompt(), "Answer clearly and keep useful context."),
                        imagePromptContext == null ? "" : imagePromptContext))
                .messages(history)
                .user(question)
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .doOnNext(finalAnswer::append)
                .doOnComplete(() -> {
                    String answer = finalAnswer.toString();
                    sessionService.saveMessage(session.getId(), "assistant", answer);
                    stepService.recordFinal(session.getId(), userMessage.getId(), answer);
                })
                .doOnError(error -> stepService.recordError(session.getId(), userMessage.getId(), error.getMessage()))
                .concatWithValues("[DONE]");
    }

    private boolean requiresReactTools(String question) {
        String value = question == null ? "" : question.toLowerCase();
        String[] toolSignals = {
                "\u6587\u6863", "\u4e0a\u4f20", "\u77e5\u8bc6\u5e93", "rag",
                "\u7b2c\u4e00\u4e2a", "\u7b2c\u4e8c\u4e2a", "\u8fd9\u4e2a\u6587\u6863", "\u90a3\u4e2a\u6587\u6863",
                "\u641c\u7d22", "\u7f51\u9875", "\u7f51\u5740", "\u94fe\u63a5", "\u6293\u53d6", "\u722c\u53d6",
                "\u4e0b\u8f7d", "\u751f\u6210pdf", "pdf", "\u5929\u6c14", "\u6700\u65b0", "\u65b0\u95fb",
                "\u8d44\u6599", "\u8c03\u7814", "\u62a5\u544a", "\u8054\u7f51",
                "\u5de5\u5177", "\u80fd\u529b", "\u4f60\u80fd\u505a\u4ec0\u4e48",
                "\u65f6\u95f4", "\u51e0\u70b9", "\u65e5\u671f", "\u4eca\u5929",
                "\u8ba1\u7b97", "\u7b97\u4e00\u4e0b", "\u7b97\u4e0b",
                "web", "search", "scrape", "download", "url", "http", "tool", "tools", "calculate"
        };
        for (String signal : toolSignals) {
            if (value.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private List<Message> buildHistoryWithSummary(AgentSession session, Long currentUserMessageId, SkillProfile skill) {
        List<AgentMessage> messages = sessionService.listMessages(session.getId());
        List<AgentMessage> previousMessages = messages.stream()
                .filter(message -> message.getId() == null || !message.getId().equals(currentUserMessageId))
                .toList();
        if (previousMessages.isEmpty()) {
            return List.of();
        }

        int recentFrom = Math.max(0, previousMessages.size() - recentMessageLimit);
        List<AgentMessage> olderMessages = previousMessages.subList(0, recentFrom);
        List<AgentMessage> recentMessages = previousMessages.subList(recentFrom, previousMessages.size());

        if (skill != null && !skill.summaryMemoryEnabled()) {
            return toSpringMessages(recentMessages, currentUserMessageId);
        }

        String summary = session.getConversationSummary();
        Long summarizedMessageId = session.getSummarizedMessageId();
        Long lastSummarizedMessageId = summarizedMessageId;
        List<AgentMessage> unsummarizedOlderMessages = olderMessages.stream()
                .filter(message -> message.getId() != null)
                .filter(message -> lastSummarizedMessageId == null || message.getId() > lastSummarizedMessageId)
                .toList();

        if (!unsummarizedOlderMessages.isEmpty()) {
            try {
                summary = summarizeHistory(summary, unsummarizedOlderMessages);
                summarizedMessageId = unsummarizedOlderMessages.get(unsummarizedOlderMessages.size() - 1).getId();
                session = sessionService.updateSummary(session.getId(), summary, summarizedMessageId);
                summary = session.getConversationSummary();
            } catch (RuntimeException ex) {
                stepService.recordError(session.getId(), currentUserMessageId,
                        "Conversation summary update failed: " + ex.getMessage());
            }
        }

        List<Message> history = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            history.add(new SystemMessage("""
                    Earlier conversation memory. Use it as background context, but prefer recent messages when details conflict:
                    %s
                    """.formatted(summary)));
        }
        history.addAll(toSpringMessages(recentMessages, currentUserMessageId));
        return history;
    }

    private String summarizeHistory(String existingSummary, List<AgentMessage> messagesToSummarize) {
        String transcript = messagesToSummarize.stream()
                .map(this::formatForSummary)
                .reduce("", (left, right) -> left + right + "\n");
        String summary = ChatClient.create(chatModel)
                .prompt()
                .system("""
                        You maintain compact long-term memory for a Chinese AI agent conversation.
                        Merge the existing memory and new transcript into one concise Chinese memory note.
                        Preserve stable user preferences, project facts, decisions, constraints, unresolved tasks, and useful technical context.
                        Remove chit-chat, duplicated wording, and obsolete details.
                        Do not mention that this is a summary.
                        """)
                .user("""
                        Existing memory:
                        %s

                        New transcript:
                        %s

                        Write the updated memory within %d Chinese characters.
                        """.formatted(blankToDefault(existingSummary, "(none)"), transcript, summaryMaxChars))
                .call()
                .content();
        return truncateSummary(summary);
    }

    private String formatForSummary(AgentMessage message) {
        String role = "assistant".equalsIgnoreCase(message.getRole()) ? "Assistant" : "User";
        String content = message.getContent() == null ? "" : message.getContent().trim();
        return role + ": " + content;
    }

    private String truncateSummary(String summary) {
        if (summary == null) {
            return "";
        }
        String value = summary.trim();
        if (value.length() <= summaryMaxChars) {
            return value;
        }
        return value.substring(0, summaryMaxChars) + "\n[summary truncated]";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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
