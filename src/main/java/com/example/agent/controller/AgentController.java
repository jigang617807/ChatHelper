package com.example.agent.controller;

import com.example.agent.entity.AgentSession;
import com.example.agent.service.AgentService;
import com.example.agent.service.AgentSessionService;
import com.example.agent.service.AgentStepService;
import com.example.agent.tool.AgentToolRegistry;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

@Controller
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final AgentService agentService;
    private final AgentToolRegistry toolRegistry;

    @GetMapping
    public String index(@RequestParam(required = false) Long sessionId, HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }

        toolRegistry.syncToolConfigs();
        AgentSession activeSession = sessionService.getOrCreateSession(userId, sessionId);
        model.addAttribute("activeSession", activeSession);
        model.addAttribute("sessions", sessionService.listActiveSessions(userId));
        model.addAttribute("messages", sessionService.listMessages(activeSession.getId()));
        model.addAttribute("steps", stepService.listSteps(activeSession.getId()));
        return "agent";
    }

    @PostMapping("/session/create")
    public String createSession(@RequestParam(required = false) String title, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        AgentSession session = sessionService.createSession(userId, title);
        return "redirect:/agent?sessionId=" + session.getId();
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam Long sessionId,
                            @RequestParam String question,
                            HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return Flux.just("Please login first.", "[DONE]");
        }
        return agentService.streamAsk(userId, sessionId, question);
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
}
