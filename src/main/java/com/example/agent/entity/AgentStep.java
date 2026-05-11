package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_step")
@Data
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;

    private Long messageId;

    private Integer stepIndex;

    @Enumerated(EnumType.STRING)
    private AgentStepType stepType;

    private String toolName;

    @Enumerated(EnumType.STRING)
    private AgentToolSource toolSource = AgentToolSource.SYSTEM;

    @Column(columnDefinition = "TEXT")
    private String toolArguments;

    @Column(columnDefinition = "TEXT")
    private String toolResult;

    @Enumerated(EnumType.STRING)
    private AgentStepStatus status = AgentStepStatus.SUCCESS;

    private Long latencyMs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime createdAt = LocalDateTime.now();
}
