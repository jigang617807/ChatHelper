package com.example.demo.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

@Controller
@RequestMapping("/auth/home")
public class HomeChatController {

    private final ChatModel deepSeekChatModel;

    public HomeChatController(@Qualifier("agentDeepSeekChatModel") ChatModel deepSeekChatModel) {
        this.deepSeekChatModel = deepSeekChatModel;
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam String question, HttpSession session) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return Flux.just("Please login first.", "[DONE]");
        }

        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            return Flux.just("Please enter a question.", "[DONE]");
        }

        return ChatClient.create(deepSeekChatModel)
                .prompt()
                .system("""
                        You are the lightweight assistant on the home page of a document RAG system.
                        Answer in Chinese. Be concise, helpful, and practical.
                        This chat is temporary and should not assume persisted conversation history.
                        """)
                .user(safeQuestion)
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .onErrorResume(error -> Flux.just("Model call failed: " + error.getMessage()))
                .concatWithValues("[DONE]");
    }
}
