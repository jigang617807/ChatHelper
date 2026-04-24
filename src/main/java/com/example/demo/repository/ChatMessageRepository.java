package com.example.demo.repository;

import com.example.demo.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(Long convId);
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long convId);

    List<ChatMessage> findByConversationId(Long id);

    @Modifying
    @Query("DELETE FROM ChatMessage c WHERE c.conversationId = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
