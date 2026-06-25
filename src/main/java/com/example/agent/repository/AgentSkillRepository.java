package com.example.agent.repository;

import com.example.agent.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, Long> {

    Optional<AgentSkill> findByCode(String code);

    List<AgentSkill> findAllByOrderByIdAsc();

    List<AgentSkill> findByEnabledTrueOrderByIdAsc();
}
