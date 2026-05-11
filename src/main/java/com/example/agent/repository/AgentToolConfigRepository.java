package com.example.agent.repository;

import com.example.agent.entity.AgentToolConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentToolConfigRepository extends JpaRepository<AgentToolConfig, Long> {

    Optional<AgentToolConfig> findByToolName(String toolName);
}
