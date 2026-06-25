package com.example.agent.controller;

import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentToolConfig;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentService;
import com.example.agent.service.AgentSkillService;
import com.example.agent.service.AgentSessionService;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.AgentToolManagementService;
import com.example.agent.tool.AgentToolRegistry;
import com.example.demo.service.ImageQuestionContext;
import com.example.demo.service.ImageQuestionContextService;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final AgentService agentService;
    private final AgentToolRegistry toolRegistry;
    private final ImageQuestionContextService imageQuestionContextService;
    private final AgentSkillService skillService;
    private final AgentToolManagementService toolManagementService;

    @Value("${agent.admin.user-ids:1}")
    private String adminUserIds;

    @GetMapping
    public String index(@RequestParam(required = false) Long sessionId, HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }

        toolRegistry.syncToolConfigs();
        skillService.ensureDefaultSkills();
        AgentSession activeSession = sessionService.getOrCreateSession(userId, sessionId);
        model.addAttribute("activeSession", activeSession);
        model.addAttribute("activeSkill", skillService.resolveSkill(activeSession.getSkillId()));
        model.addAttribute("skills", skillService.listEnabledSkills());
        model.addAttribute("sessions", sessionService.listActiveSessions(userId));
        model.addAttribute("messages", sessionService.listMessages(activeSession.getId()));
        model.addAttribute("steps", stepService.listSteps(activeSession.getId()));
        return "agent";
    }

    @PostMapping("/session/create")
    public String createSession(@RequestParam(required = false) String title,
                                @RequestParam(required = false) Long skillId,
                                HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        AgentSession session = sessionService.createSession(userId, title, skillId);
        return "redirect:/agent?sessionId=" + session.getId();
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam Long sessionId,
                            @RequestParam String question,
                            @RequestParam(required = false) String imageContextId,
                            HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return Flux.just("Please login first.", "[DONE]");
        }
        ImageQuestionContext imageContext = imageQuestionContextService.find(userId, imageContextId);
        return agentService.streamAsk(userId, sessionId, question, imageContext);
    }

    @PostMapping("/image-context")
    @ResponseBody
    public ResponseEntity<?> uploadImageContext(@RequestParam("file") MultipartFile file, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        try {
            ImageQuestionContext context = imageQuestionContextService.save(userId, file);
            return ResponseEntity.ok(Map.of(
                    "id", context.id(),
                    "webPath", context.webPath(),
                    "description", context.description()
            ));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @GetMapping("/steps")
    @ResponseBody
    public ResponseEntity<?> steps(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        return ResponseEntity.ok(stepService.listSteps(session.getId()));
    }

    @GetMapping("/admin")
    public String admin(HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        skillService.ensureDefaultSkills();
        List<AgentToolConfig> tools = toolManagementService.listTools();
        model.addAttribute("tools", tools);
        model.addAttribute("skills", skillService.listSkillProfiles());
        model.addAttribute("toolCount", tools.size());
        model.addAttribute("mcpToolCount", tools.stream()
                .filter(tool -> tool.getToolSource() == AgentToolSource.MCP)
                .count());
        model.addAttribute("enabledToolCount", tools.stream()
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()))
                .count());
        return "agent-admin";
    }

    @GetMapping("/tools")
    @ResponseBody
    public ResponseEntity<?> tools(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        return ResponseEntity.ok(toolManagementService.listTools());
    }

    @PostMapping("/tools/{toolName}/enabled")
    @ResponseBody
    public ResponseEntity<?> setToolEnabled(@PathVariable String toolName,
                                            @RequestParam boolean enabled,
                                            HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body("Admin permission is required.");
        }
        return ResponseEntity.ok(toolManagementService.setToolEnabled(toolName, enabled));
    }

    @PostMapping("/admin/tools/{toolName}/enabled")
    public String setToolEnabledFromAdmin(@PathVariable String toolName,
                                          @RequestParam boolean enabled,
                                          HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        toolManagementService.setToolEnabled(toolName, enabled);
        return "redirect:/agent/admin";
    }

    @GetMapping("/skills")
    @ResponseBody
    public ResponseEntity<?> skills(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        skillService.ensureDefaultSkills();
        return ResponseEntity.ok(skillService.listEnabledSkillProfiles());
    }

    @PostMapping("/skills/{skillId}/enabled")
    @ResponseBody
    public ResponseEntity<?> setSkillEnabled(@PathVariable Long skillId,
                                             @RequestParam boolean enabled,
                                             HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body("Admin permission is required.");
        }
        return ResponseEntity.ok(skillService.setSkillEnabled(skillId, enabled));
    }

    @PostMapping("/admin/skills/{skillId}/enabled")
    public String setSkillEnabledFromAdmin(@PathVariable Long skillId,
                                           @RequestParam boolean enabled,
                                           HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        skillService.setSkillEnabled(skillId, enabled);
        return "redirect:/agent/admin";
    }

    @PostMapping("/skills/{skillId}/tools")
    @ResponseBody
    public ResponseEntity<?> replaceSkillTools(@PathVariable Long skillId,
                                               @RequestParam(required = false) List<String> toolNames,
                                               HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body("Admin permission is required.");
        }
        return ResponseEntity.ok(skillService.replaceSkillTools(skillId, toolNames));
    }

    @PostMapping("/admin/skills/{skillId}/tools")
    public String replaceSkillToolsFromAdmin(@PathVariable Long skillId,
                                             @RequestParam(required = false) List<String> toolNames,
                                             HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        skillService.replaceSkillTools(skillId, toolNames);
        return "redirect:/agent/admin";
    }

    @PostMapping("/session/skill")
    @ResponseBody
    public ResponseEntity<?> updateSessionSkill(@RequestParam Long sessionId,
                                                @RequestParam(required = false) Long skillId,
                                                HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        return ResponseEntity.ok(sessionService.updateSkill(userId, sessionId, skillId));
    }

    @PostMapping("/clear")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> clear(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        sessionService.clearSession(userId, sessionId);
        return ResponseEntity.ok("Agent session cleared.");
    }

    private boolean isAdmin(Long userId) {
        if (userId == null || adminUserIds == null || adminUserIds.isBlank()) {
            return false;
        }
        return Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(String.valueOf(userId)));
    }
}
