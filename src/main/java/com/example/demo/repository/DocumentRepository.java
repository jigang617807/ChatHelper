package com.example.demo.repository;

import com.example.demo.entity.Document;
import com.example.demo.entity.DocStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByUserId(Long userId);

    List<Document> findByStatusIn(List<DocStatus> statuses);

    Optional<Document> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Document d
            SET d.status = :processingStatus,
                d.processingStartedAt = :startedAt,
                d.errorMessage = null
            WHERE d.id = :id
              AND d.status IN :allowedStatuses
            """)
    int markProcessing(@Param("id") Long id,
                       @Param("allowedStatuses") List<DocStatus> allowedStatuses,
                       @Param("processingStatus") DocStatus processingStatus,
                       @Param("startedAt") LocalDateTime startedAt);

}

