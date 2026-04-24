package com.example.demo.controller;

import com.example.demo.entity.Conversation;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ConversationRepository;
import com.example.demo.repository.DocumentChunkProjection;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.RagService;
import com.example.demo.service.SpringAiService;
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

import java.util.List;

@Controller
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    private final RagService ragService;
    private final DocumentRepository documentRepository;
    private final SpringAiService springAiService;

    @GetMapping("/start")
    public String start(@RequestParam Long docId, HttpSession session, Model model) {
        Long uid = (Long) session.getAttribute("uid");
        String title = "Doc-" + docId + " 对话";

        Conversation conv = convRepo.findTopByUserIdAndTitle(uid, title)
                .orElseGet(() -> {
                    Conversation c = new Conversation();
                    c.setUserId(uid);
                    c.setTitle(title);
                    return convRepo.save(c);
                });

        model.addAttribute("conversationId", conv.getId());
        model.addAttribute("documentId", docId);
        model.addAttribute("messages", msgRepo.findByConversationIdOrderByIdAsc(conv.getId()));
        return "chat";
    }

    @PostMapping("/clear")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> clearConversation(@RequestParam("documentId") Long documentId, HttpSession session) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return ResponseEntity.status(401).body("未登录或会话已过期");
        }

        if (documentId == null) {
            return ResponseEntity.badRequest().body("文档ID不能为空");
        }

        try {
            String title = "Doc-" + documentId + " 对话";

            convRepo.findTopByUserIdAndTitle(uid, title).ifPresent(conversation -> {
                Long conversationId = conversation.getId();
                msgRepo.deleteByConversationId(conversationId);
                convRepo.deleteById(conversationId);
            });

            return ResponseEntity.ok("聊天记录已清空");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("清空失败: " + e.getMessage());
        }
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam Long conversationId,
                            @RequestParam Long documentId,
                            @RequestParam String question,
                            HttpSession session) {

        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return Flux.just("data:【错误】用户未登录或会话已过期");
        }

        boolean owned = documentRepository.existsByIdAndUserId(documentId, uid);
        if (!owned) {
            return Flux.just("data:【错误】无权访问该文档");
        }

        List<DocumentChunkProjection> chunks = ragService.searchRelevant(documentId, question);
        StringBuilder context = new StringBuilder();
        for (DocumentChunkProjection chunk : chunks) {
            context.append(chunk.getText()).append("\n");
        }

        String ragPrompt = "参考文档片段:\n" + context + "\n用户问题:\n" + question;
        List<com.example.demo.entity.ChatMessage> history = msgRepo.findByConversationIdOrderByIdAsc(conversationId);

        com.example.demo.entity.ChatMessage user = new com.example.demo.entity.ChatMessage();
        user.setConversationId(conversationId);
        user.setRole("user");
        user.setMessage(question);
        msgRepo.save(user);

        StringBuilder finalReply = new StringBuilder();

        return Flux.create(sink -> springAiService.streamAnswer(history, ragPrompt).subscribe(
                chunk -> {
                    finalReply.append(chunk);
                    sink.next(chunk);
                },
                err -> {
                    sink.next("data:【错误】" + err.getMessage());
                    sink.complete();
                },
                () -> {
                    com.example.demo.entity.ChatMessage ai = new com.example.demo.entity.ChatMessage();
                    ai.setConversationId(conversationId);
                    ai.setRole("assistant");
                    ai.setMessage(finalReply.toString());
                    msgRepo.save(ai);

                    sink.next("[DONE]");
                    sink.complete();
                }
        ));
    }
}
