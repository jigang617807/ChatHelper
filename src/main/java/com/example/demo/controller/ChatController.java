package com.example.demo.controller;

import com.example.demo.entity.ChatMessage;
import com.example.demo.entity.Conversation;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ConversationRepository;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.RagCachedRetrievalService;
import com.example.demo.service.RagSearchResult;
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
    private final RagCachedRetrievalService ragCachedRetrievalService;
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
            return Flux.just("【错误】用户未登录或会话已过期", "[DONE]");
        }

        boolean owned = documentRepository.existsByIdAndUserId(documentId, uid);
        if (!owned) {
            return Flux.just("【错误】无权访问该文档", "[DONE]");
        }

        RagSearchResult searchResult = ragCachedRetrievalService.search(uid, documentId, question);
        String ragPrompt = """
                你是企业文档知识库助手。请严格基于“检索证据”回答用户问题，不要编造证据外的信息。
                如果证据不足，请明确说明“当前文档证据不足”，并给出还需要补充哪些信息。
                回答中的关键结论后请尽量标注引用编号，例如 [S1]、[S2]。

                检索证据：
                %s

                用户问题：
                %s

                输出要求：
                1. 使用简体中文。
                2. 先直接回答，再补充依据。
                3. 不要输出没有证据支撑的确定性结论。
                """.formatted(searchResult.buildPromptContext(), question);
        List<ChatMessage> history = msgRepo.findByConversationIdOrderByIdAsc(conversationId);

        ChatMessage user = new ChatMessage();
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
                    sink.next("【错误】" + err.getMessage());
                    sink.next("[DONE]");
                    sink.complete();
                },
                () -> {
                    String citationSummary = searchResult.buildCitationSummary();
                    finalReply.append(citationSummary);
                    sink.next(citationSummary);

                    ChatMessage ai = new ChatMessage();
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
