package com.example.agent.repository;

import com.example.agent.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
