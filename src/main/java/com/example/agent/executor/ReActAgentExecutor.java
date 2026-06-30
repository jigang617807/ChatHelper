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

    private static final int MAX_RAW_MODEL_TEXT_LENGTH = 4000;

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
                String answer = synthesizeFinalAnswer(question, history, observations, plan, activeSkill);
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
                    observations.add(formatObservation(step, tool.name(), true, result.toObservation()));
                    String answer = synthesizeFinalAnswer(question, history, observations,
                            "Terminate tool requested final answer synthesis.", activeSkill);
                    stepService.recordFinal(sessionId, messageId, answer);
                    return answer;
                }
            } else {
                stepService.recordToolError(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.errorMessage(), latencyMs);
            }
            observations.add(formatObservation(step, tool.name(), result.success(), result.toObservation()));
        }

        String answer = synthesizeFinalAnswer(question, history, observations,
                "The agent reached the maximum number of ReAct steps. Provide the best possible partial answer.",
                activeSkill);
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
        String raw = extractText(response);
        try {
            return ReActAction.parseRequired(raw, objectMapper);
        } catch (IllegalArgumentException firstError) {
            try {
                String repaired = repairActionJson(raw, firstError.getMessage(), skill);
                return ReActAction.parseRequired(repaired, objectMapper);
            } catch (RuntimeException repairError) {
                return new ReActAction("finish",
                        "Planner output could not be parsed after repair; answer directly from available context.",
                        null, null, "");
            }
        }
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
                  "plan": "why enough information has been collected for the final answer"
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
                9. Never put the final user-facing answer in this JSON. Final answers are generated by a separate answer synthesizer.
                10. When observations contain rag_search evidence like [S1], [S2], preserve those citation ids in the final answer.
                11. For document-grounded answers, add a final "引用来源" section listing citation id, document title/id, chunk index and relevance when available.
                12. If rag_search reports answer confidence, include it briefly and explain when evidence is insufficient.
                13. If the user asks whether a previous answer, plan, report or conclusion is based on today, current, latest or real-time conditions, first call date_time.
                14. After date_time, call web_search when the previous answer depends on weather, opening hours, schedules, policy, prices, news, availability, travel, public facts or other external current conditions.
                15. If a needed tool is unavailable or fails, finish and let the answer synthesizer explain the limitation instead of inventing data.

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
                For follow-up questions about whether a previous answer is based on today/current/latest information, use tools to verify time and external current facts before finishing whenever those tools are available.
                """.formatted(question, joinedObservations, step, maxSteps);
    }

    private String repairActionJson(String rawText, String parseError, SkillProfile skill) {
        Set<String> allowedTools = skill.allowedTools() == null ? Set.of() : skill.allowedTools();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("""
                You repair malformed ReAct planner output.
                Return exactly one valid JSON object and no Markdown fences.
                Do not answer the user. Do not include final prose.

                Valid tool action:
                {"type":"tool","plan":"brief reason","toolName":"one available tool name","arguments":{}}

                Valid finish action:
                {"type":"finish","plan":"why enough information has been collected"}

                Available tools:
                %s
                """.formatted(toolRegistry.toolDescriptions(allowedTools))));
        messages.add(new UserMessage("""
                Parse error:
                %s

                Original model output:
                %s

                Repair it into one valid ReAct JSON object.
                """.formatted(parseError, truncateRaw(rawText))));
        return extractText(chatModel.call(new Prompt(messages)));
    }

    private String synthesizeFinalAnswer(String question,
                                         List<Message> history,
                                         List<String> observations,
                                         String completionReason,
                                         SkillProfile skill) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("""
                You are the final answer synthesizer for this Agentic RAG system.
                Active skill: %s (%s)
                Skill instructions:
                %s

                Answer the user in Chinese with clear Markdown.
                Do not output ReAct JSON.
                Use the provided tool observations as factual evidence.
                If observations are missing, failed, or insufficient, say so clearly and answer from the conversation context only where reasonable.
                If current-date, latest, weather, schedule, opening-hours, price, policy, news or availability claims are involved, distinguish verified current facts from assumptions.
                Preserve citation ids from RAG observations when present.
                """.formatted(
                skill.name(),
                skill.code(),
                blankToDefault(skill.systemPrompt(), "Answer clearly and keep useful context."))));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage("""
                Current user request:
                %s

                ReAct completion reason:
                %s

                Tool observations collected in this run:
                %s

                Write the final user-facing answer now.
                """.formatted(
                question,
                blankToDefault(completionReason, "Enough information has been collected."),
                observations.isEmpty() ? "(none)" : String.join("\n\n", observations))));
        return blankToDefault(extractText(chatModel.call(new Prompt(messages))),
                "抱歉，我暂时无法生成稳定的最终回答。请稍后重试，或把问题拆成更具体的一步。");
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

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncateRaw(String value) {
        if (value == null || value.length() <= MAX_RAW_MODEL_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RAW_MODEL_TEXT_LENGTH) + "\n[TRUNCATED]";
    }
}
