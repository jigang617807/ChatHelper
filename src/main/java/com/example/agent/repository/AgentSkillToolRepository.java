package com.example.agent.repository;

import com.example.agent.entity.AgentSkillTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSkillToolRepository extends JpaRepository<AgentSkillTool, Long> {

    List<AgentSkillTool> findBySkillIdOrderBySortOrderAscIdAsc(Long skillId);

    void deleteBySkillId(Long skillId);
}
