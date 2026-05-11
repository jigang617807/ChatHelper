package com.example.agent.service;

import com.example.agent.entity.AgentStep;
import com.example.agent.entity.AgentStepStatus;
import com.example.agent.entity.AgentStepType;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.repository.AgentStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentStepService {

    private static final int MAX_STORED_TEXT_LENGTH = 12000;

    private final AgentStepRepository stepRepository;

    public List<AgentStep> listSteps(Long sessionId) {
        return stepRepository.findBySessionIdOrderByStepIndexAscIdAsc(sessionId);
    }

    @Transactional
    public AgentStep recordPlan(Long sessionId, Long messageId, String plan) {
        return save(sessionId, messageId, AgentStepType.PLAN, "agent_planner", AgentToolSource.SYSTEM,
                null, plan, AgentStepStatus.SUCCESS, null, null);
    }

    @Transactional
    public AgentStep recordFinal(Long sessionId, Long messageId, String finalAnswer) {
        return save(sessionId, messageId, AgentStepType.FINAL, "agent_final_answer", AgentToolSource.SYSTEM,
                null, finalAnswer, AgentStepStatus.SUCCESS, null, null);
    }

    @Transactional
    public AgentStep recordError(Long sessionId, Long messageId, String message) {
        return save(sessionId, messageId, AgentStepType.ERROR, "agent_error", AgentToolSource.SYSTEM,
                null, null, AgentStepStatus.FAILED, null, message);
    }

    @Transactional
    public AgentStep recordToolCall(Long sessionId, String toolName, AgentToolSource source, String arguments) {
        return save(sessionId, null, AgentStepType.TOOL_CALL, toolName, source,
                arguments, null, AgentStepStatus.RUNNING, null, null);
    }

    @Transactional
    public AgentStep recordToolResult(Long sessionId, String toolName, AgentToolSource source, String arguments,
                                      String result, long latencyMs) {
        return save(sessionId, null, AgentStepType.TOOL_RESULT, toolName, source,
                arguments, result, AgentStepStatus.SUCCESS, latencyMs, null);
    }

    @Transactional
    public AgentStep recordToolError(Long sessionId, String toolName, AgentToolSource source, String arguments,
                                     String error, long latencyMs) {
        return save(sessionId, null, AgentStepType.TOOL_RESULT, toolName, source,
                arguments, null, AgentStepStatus.FAILED, latencyMs, error);
    }

    private AgentStep save(Long sessionId, Long messageId, AgentStepType type, String toolName, AgentToolSource source,
                           String arguments, String result, AgentStepStatus status, Long latencyMs, String error) {
        AgentStep step = new AgentStep();
        step.setSessionId(sessionId);
        step.setMessageId(messageId);
        step.setStepIndex((int) stepRepository.countBySessionId(sessionId) + 1);
        step.setStepType(type);
        step.setToolName(toolName);
        step.setToolSource(source);
        step.setToolArguments(truncate(arguments));
        step.setToolResult(truncate(result));
        step.setStatus(status);
        step.setLatencyMs(latencyMs);
        step.setErrorMessage(truncate(error));
        return stepRepository.save(step);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_STORED_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STORED_TEXT_LENGTH) + "\n\n[TRUNCATED]";
    }
}
