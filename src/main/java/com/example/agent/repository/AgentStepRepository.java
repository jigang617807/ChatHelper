package com.example.agent.repository;

import com.example.agent.entity.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findBySessionIdOrderByStepIndexAscIdAsc(Long sessionId);

    long countBySessionId(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
