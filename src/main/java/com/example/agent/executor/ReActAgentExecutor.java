package com.example.agent.executor;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentSkillService.SkillProfile;
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
import java.util.Set;

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

    public String execute(Long userId,
                          Long sessionId,
                          Long messageId,
                          String question,
                          List<Message> history,
                          SkillProfile skill) {
        Path workspace = workspaceService.workspace(userId, sessionId);
        SkillProfile activeSkill = skill == null ? defaultSkill() : skill;
        Set<String> allowedTools = activeSkill.allowedTools() == null ? Set.of() : activeSkill.allowedTools();
        ToolExecutionContext context = new ToolExecutionContext(
                userId, sessionId, messageId, workspace, activeSkill.id(), activeSkill.name(), allowedTools);
        List<String> observations = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            ReActAction action = nextAction(question, history, observations, step, activeSkill);
            String plan = blankToDefault(action.plan(), "Decide the next action.");
            stepService.recordPlan(sessionId, messageId, "Step " + step + ": " + plan);

            if (action.isFinish()) {
                String answer = blankToDefault(action.finalAnswer(), "Task completed.");
                stepService.recordFinal(sessionId, messageId, answer);
                return answer;
            }

            if (!action.isTool()) {
                String answer = "Unsupported model action type: " + action.type();
                stepService.recordError(sessionId, messageId, answer);
                return answer;
            }

            ReactTool tool = toolRegistry.find(action.toolName(), allowedTools).orElse(null);
            if (tool == null) {
                String observation = "Unknown or disabled tool for current skill: " + action.toolName() + ". Available tools: " + toolRegistry.list(allowedTools).stream()
                        .map(ReactTool::name)
                        .toList();
                stepService.recordToolError(sessionId, messageId, action.toolName(), AgentToolSource.LOCAL,
                        toJson(action.arguments()), observation, 0);
                observations.add(formatObservation(step, action.toolName(), false, observation));
                continue;
            }

            long startedAt = System.currentTimeMillis();
            String argumentsJson = toJson(action.arguments());
            stepService.recordToolCall(sessionId, messageId, tool.name(), tool.source(), argumentsJson);
            ToolExecutionResult result;
            try {
                result = tool.execute(context, action.arguments());
            } catch (RuntimeException ex) {
                result = ToolExecutionResult.failure(ex.getMessage());
            }
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (result.success()) {
                stepService.recordToolResult(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.toObservation(), latencyMs);
                if ("terminate".equals(tool.name())) {
                    String answer = blankToDefault(result.content(), "Task terminated.");
                    stepService.recordFinal(sessionId, messageId, answer);
                    return answer;
                }
            } else {
                stepService.recordToolError(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.errorMessage(), latencyMs);
            }
            observations.add(formatObservation(step, tool.name(), result.success(), result.toObservation()));
        }

        String answer = buildMaxStepAnswer(observations);
        stepService.recordError(sessionId, messageId, "Max ReAct steps exceeded.");
        stepService.recordFinal(sessionId, messageId, answer);
        return answer;
    }

    private ReActAction nextAction(String question,
                                   List<Message> history,
                                   List<String> observations,
                                   int step,
                                   SkillProfile skill) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(skill)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userPrompt(question, observations, step)));
        ChatResponse response = chatModel.call(new Prompt(messages));
        return ReActAction.fromModelText(extractText(response), objectMapper);
    }

    private String systemPrompt(SkillProfile skill) {
        SkillProfile activeSkill = skill == null ? defaultSkill() : skill;
        Set<String> allowedTools = activeSkill.allowedTools() == null ? Set.of() : activeSkill.allowedTools();
        return """
                You are a ReAct-style AI agent in this Java Spring system.
                Active skill: %s (%s)
                Skill instructions:
                %s

                You must respond with exactly one JSON object and no Markdown fences.
                The JSON must be valid. Escape newlines in string values as \\n.

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
                0. If the user asks what tools or capabilities you have, call tool_list.
                1. Use tools when external web information, uploaded documents, downloads, or PDF artifacts are needed.
                2. For uploaded documents, call document_list first if the target document id is unclear, then call rag_search.
                3. For public web research, call web_search first, then web_scraping for the most relevant URLs.
                4. For single-document questions, rag_search should include documentId.
                5. For current date/time questions, call date_time.
                6. For arithmetic questions, call calculator.
                7. Do not invent tool results. Base the final answer on observations.
                8. Finish as soon as the task is sufficiently answered.
                9. Never expose the ReAct JSON to the user. Put user-facing content only in finalAnswer.
                10. When observations contain rag_search evidence like [S1], [S2], preserve those citation ids in the final answer.
                11. For document-grounded answers, add a final "引用来源" section listing citation id, document title/id, chunk index and relevance when available.
                12. If rag_search reports answer confidence, include it briefly and explain when evidence is insufficient.

                Available tools:
                %s
                """.formatted(
                activeSkill.name(),
                activeSkill.code(),
                blankToDefault(activeSkill.systemPrompt(), "Use the available tools only when they help the user's task."),
                toolRegistry.toolDescriptions(allowedTools));
    }

    private SkillProfile defaultSkill() {
        return new SkillProfile(null, "general", "General Agent", "", "", "SUMMARY", true, Set.of());
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
                The agent reached the maximum number of ReAct steps. Here is the best partial result based on collected observations.

                %s
                """.formatted(observations.isEmpty() ? "No tool observations are available yet." : String.join("\n\n", observations));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
