package com.example.agent.executor;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentStepService;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ReactTool;
import com.example.agent.tool.react.ReactToolRegistry;
import com.example.agent.tool.react.ToolExecutionContext;
import com.example.agent.tool.react.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReActAgentExecutor {

    private final ChatModel chatModel;
    private final ReactToolRegistry toolRegistry;
    private final AgentStepService stepService;
    private final AgentWorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final int maxSteps;

    public ReActAgentExecutor(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                              ReactToolRegistry toolRegistry,
                              AgentStepService stepService,
                              AgentWorkspaceService workspaceService,
                              ObjectMapper objectMapper,
                              @Value("${agent.react.max-steps:8}") int maxSteps) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.stepService = stepService;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
        this.maxSteps = Math.max(1, Math.min(maxSteps, 16));
    }

    public String execute(Long userId, Long sessionId, Long messageId, String question, List<Message> history) {
        Path workspace = workspaceService.workspace(userId, sessionId);
        ToolExecutionContext context = new ToolExecutionContext(userId, sessionId, messageId, workspace);
        List<String> observations = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            ReActAction action = nextAction(question, history, observations, step);
            String plan = blankToDefault(action.plan(), "Decide the next action.");
            stepService.recordPlan(sessionId, messageId, "Step " + step + ": " + plan);

            if (action.isFinish()) {
                String answer = blankToDefault(action.finalAnswer(), "任务已完成。");
                stepService.recordFinal(sessionId, messageId, answer);
                return answer;
            }

            if (!action.isTool()) {
                String answer = "模型返回了不支持的动作类型：" + action.type();
                stepService.recordError(sessionId, messageId, answer);
                return answer;
            }

            ReactTool tool = toolRegistry.find(action.toolName()).orElse(null);
            if (tool == null) {
                String observation = "Unknown tool: " + action.toolName() + ". Available tools: " + toolRegistry.list().stream()
                        .map(ReactTool::name)
                        .toList();
                stepService.recordToolError(sessionId, messageId, action.toolName(), AgentToolSource.LOCAL,
                        toJson(action.arguments()), observation, 0);
                observations.add(formatObservation(step, action.toolName(), false, observation));
                continue;
            }

            long startedAt = System.currentTimeMillis();
            String argumentsJson = toJson(action.arguments());
            stepService.recordToolCall(sessionId, messageId, tool.name(), AgentToolSource.LOCAL, argumentsJson);
            ToolExecutionResult result;
            try {
                result = tool.execute(context, action.arguments());
            } catch (RuntimeException ex) {
                result = ToolExecutionResult.failure(ex.getMessage());
            }
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (result.success()) {
                stepService.recordToolResult(sessionId, messageId, tool.name(), AgentToolSource.LOCAL,
                        argumentsJson, result.toObservation(), latencyMs);
                if ("terminate".equals(tool.name())) {
                    String answer = blankToDefault(result.content(), "任务已结束。");
                    stepService.recordFinal(sessionId, messageId, answer);
                    return answer;
                }
            } else {
                stepService.recordToolError(sessionId, messageId, tool.name(), AgentToolSource.LOCAL,
                        argumentsJson, result.errorMessage(), latencyMs);
            }
            observations.add(formatObservation(step, tool.name(), result.success(), result.toObservation()));
        }

        String answer = buildMaxStepAnswer(observations);
        stepService.recordError(sessionId, messageId, "Max ReAct steps exceeded.");
        stepService.recordFinal(sessionId, messageId, answer);
        return answer;
    }

    private ReActAction nextAction(String question, List<Message> history, List<String> observations, int step) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt()));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userPrompt(question, observations, step)));
        ChatResponse response = chatModel.call(new Prompt(messages));
        String text = extractText(response);
        return ReActAction.fromModelText(text, objectMapper);
    }

    private String systemPrompt() {
        return """
                You are a ReAct-style AI agent in this Java Spring system.

                You must respond with exactly one JSON object and no Markdown fences.
                JSON schema for using a tool:
                {
                  "type": "tool",
                  "plan": "brief reason for this step",
                  "toolName": "one available tool name",
                  "arguments": {}
                }

                JSON schema for finishing:
                {
                  "type": "finish",
                  "plan": "why the task is complete",
                  "finalAnswer": "final answer to the user in Chinese Markdown"
                }

                Rules:
                1. Use tools when external web information, uploaded documents, downloads, or PDF artifacts are needed.
                2. For uploaded documents, call document_list first if the target document id is unclear, then call rag_search.
                3. For public web research, call web_search first, then web_scraping for the most relevant URLs.
                4. For single-document questions, rag_search should include documentId.
                5. Do not invent tool results. Base the final answer on observations.
                6. Finish as soon as the task is sufficiently answered.

                Available tools:
                %s
                """.formatted(toolRegistry.toolDescriptions());
    }

    private String userPrompt(String question, List<String> observations, int step) {
        String joinedObservations = observations.isEmpty()
                ? "(none yet)"
                : String.join("\n\n", observations);
        return """
                Current user request:
                %s

                Previous observations:
                %s

                You are at ReAct step %d of %d. Choose the next tool action or finish.
                """.formatted(question, joinedObservations, step, maxSteps);
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String formatObservation(int step, String toolName, boolean success, String observation) {
        return "Observation " + step + " from " + toolName + " (" + (success ? "success" : "failed") + "):\n" + observation;
    }

    private String buildMaxStepAnswer(List<String> observations) {
        return """
                已达到本轮 Agent 最大执行步数限制，我先基于当前已获得的信息给出阶段性结果。

                %s
                """.formatted(observations.isEmpty() ? "当前还没有可用工具结果。" : String.join("\n\n", observations));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
