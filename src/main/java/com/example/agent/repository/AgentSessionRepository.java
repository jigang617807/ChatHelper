package com.example.agent.repository;

import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    List<AgentSession> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, AgentSessionStatus status);

    Optional<AgentSession> findByIdAndUserId(Long id, Long userId);
}
