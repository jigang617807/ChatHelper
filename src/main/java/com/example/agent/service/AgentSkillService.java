package com.example.agent.service;

import com.example.agent.entity.AgentSkill;
import com.example.agent.entity.AgentSkillTool;
import com.example.agent.repository.AgentSkillRepository;
import com.example.agent.repository.AgentSkillToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentSkillService {

    public static final String DEFAULT_SKILL_CODE = "general";
    private static final String MEMORY_SUMMARY = "SUMMARY";
    private static final String MEMORY_RECENT_ONLY = "RECENT_ONLY";

    private final AgentSkillRepository skillRepository;
    private final AgentSkillToolRepository skillToolRepository;

    public List<AgentSkill> listEnabledSkills() {
        return skillRepository.findByEnabledTrueOrderByIdAsc();
    }

    public List<SkillProfile> listEnabledSkillProfiles() {
        return listEnabledSkills().stream()
                .map(this::toProfile)
                .toList();
    }

    public SkillProfile resolveSkill(Long skillId) {
        AgentSkill skill = null;
        if (skillId != null) {
            skill = skillRepository.findById(skillId)
                    .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                    .orElse(null);
        }
        if (skill == null) {
            skill = skillRepository.findByCode(DEFAULT_SKILL_CODE)
                    .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                    .orElseGet(this::fallbackGeneralSkill);
        }
        return toProfile(skill);
    }

    public SkillProfile resolveDefaultSkill() {
        return resolveSkill(null);
    }

    @Transactional
    public void ensureDefaultSkills() {
        ensureSkill(
                DEFAULT_SKILL_CODE,
                "General Agent",
                "Default workspace for document search, web research, artifact generation and utility tools.",
                """
                        You are a general Agentic RAG assistant. Choose tools only when they help the user's task.
                        Preserve citation ids from RAG evidence and keep final answers concise and verifiable.
                        """,
                MEMORY_SUMMARY,
                List.of("tool_list", "document_list", "rag_search", "web_search", "web_scraping",
                        "resource_download", "pdf_generation", "calculator", "date_time", "terminate")
        );
        ensureSkill(
                "document_qa",
                "Document QA",
                "Grounded Q&A over the user's uploaded private documents.",
                """
                        Focus on private document Q&A. Prefer document_list when the target document is unclear, then use rag_search.
                        Do not answer document-specific claims without retrieved evidence. Preserve citation ids.
                        """,
                MEMORY_SUMMARY,
                List.of("document_list", "rag_search", "terminate")
        );
        ensureSkill(
                "research_report",
                "Research Report",
                "Combine private document evidence, public web research and PDF report generation.",
                """
                        Plan the task as evidence collection followed by report synthesis.
                        Use private RAG evidence first, then web tools when public information is required, and generate artifacts only after enough evidence is collected.
                        """,
                MEMORY_SUMMARY,
                List.of("document_list", "rag_search", "web_search", "web_scraping", "resource_download",
                        "pdf_generation", "calculator", "date_time", "terminate")
        );
        ensureSkill(
                "quick_tools",
                "Quick Tools",
                "Lightweight utility mode for date/time, calculation and tool discovery.",
                """
                        Use utility tools directly and avoid unnecessary RAG or web calls.
                        """,
                MEMORY_RECENT_ONLY,
                List.of("tool_list", "calculator", "date_time", "terminate")
        );
    }

    private SkillProfile toProfile(AgentSkill skill) {
        Set<String> allowedTools = new LinkedHashSet<>();
        if (skill.getId() != null) {
            for (AgentSkillTool tool : skillToolRepository.findBySkillIdOrderBySortOrderAscIdAsc(skill.getId())) {
                if (tool.getToolName() != null && !tool.getToolName().isBlank()) {
                    allowedTools.add(tool.getToolName());
                }
            }
        }
        return new SkillProfile(
                skill.getId(),
                blankToDefault(skill.getCode(), DEFAULT_SKILL_CODE),
                blankToDefault(skill.getName(), "General Agent"),
                blankToDefault(skill.getDescription(), ""),
                blankToDefault(skill.getSystemPrompt(), ""),
                blankToDefault(skill.getMemoryStrategy(), MEMORY_SUMMARY),
                allowedTools
        );
    }

    private AgentSkill fallbackGeneralSkill() {
        AgentSkill skill = new AgentSkill();
        skill.setCode(DEFAULT_SKILL_CODE);
        skill.setName("General Agent");
        skill.setDescription("Fallback general skill. Persistent defaults are initialized when the database is available.");
        skill.setSystemPrompt("Use the available tools only when they help the user's task.");
        skill.setMemoryStrategy(MEMORY_SUMMARY);
        return skill;
    }

    private void ensureSkill(String code,
                             String name,
                             String description,
                             String systemPrompt,
                             String memoryStrategy,
                             List<String> tools) {
        AgentSkill skill = skillRepository.findByCode(code).orElseGet(() -> {
            AgentSkill created = new AgentSkill();
            created.setCode(code);
            created.setEnabled(true);
            return created;
        });
        skill.setName(name);
        skill.setDescription(description);
        skill.setSystemPrompt(systemPrompt.strip());
        skill.setMemoryStrategy(memoryStrategy);
        skill.setEnabled(true);
        skill = skillRepository.save(skill);

        if (skillToolRepository.findBySkillIdOrderBySortOrderAscIdAsc(skill.getId()).isEmpty()) {
            int order = 0;
            for (String toolName : tools) {
                AgentSkillTool link = new AgentSkillTool();
                link.setSkillId(skill.getId());
                link.setToolName(toolName);
                link.setSortOrder(order++);
                skillToolRepository.save(link);
            }
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record SkillProfile(
            Long id,
            String code,
            String name,
            String description,
            String systemPrompt,
            String memoryStrategy,
            Set<String> allowedTools
    ) {
        public boolean summaryMemoryEnabled() {
            return !MEMORY_RECENT_ONLY.equalsIgnoreCase(memoryStrategy);
        }

        public boolean restrictsTools() {
            return allowedTools != null && !allowedTools.isEmpty();
        }
    }
}
