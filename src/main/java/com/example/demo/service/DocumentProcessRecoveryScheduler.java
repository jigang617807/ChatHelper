package com.example.demo.service;

import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessRecoveryScheduler {

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;

    @Value("${document.processing.recovery-enabled:true}")
    private boolean recoveryEnabled;

    @Value("${document.processing.recovery-batch-size:20}")
    private int batchSize;

    @Value("${document.processing.stale-processing-minutes:30}")
    private long staleProcessingMinutes;

    @Scheduled(fixedDelayString = "${document.processing.recovery-interval-ms:60000}")
    public void republishUnfinishedDocuments() {
        if (!recoveryEnabled) {
            return;
        }

        List<Document> candidates = documentRepository.findByStatusIn(List.of(
                DocStatus.PENDING,
                DocStatus.FAILED_RETRYABLE,
                DocStatus.PROCESSING
        ));

        candidates.stream()
                .filter(this::shouldRepublish)
                .limit(Math.max(1, batchSize))
                .forEach(this::republish);
    }

    private boolean shouldRepublish(Document doc) {
        if (doc.getStatus() == DocStatus.PENDING || doc.getStatus() == DocStatus.FAILED_RETRYABLE) {
            return true;
        }
        if (doc.getStatus() != DocStatus.PROCESSING) {
            return false;
        }
        if (doc.getProcessingStartedAt() == null) {
            return true;
        }
        long minutes = Math.max(1, staleProcessingMinutes);
        return doc.getProcessingStartedAt().plus(Duration.ofMinutes(minutes)).isBefore(LocalDateTime.now());
    }

    private void republish(Document doc) {
        try {
            int retryCount = doc.getRetryCount() == null ? 0 : doc.getRetryCount();
            documentService.sendProcessMessage(doc.getId(), retryCount);
            log.info("Republished unfinished document processing task. docId={}, status={}, retryCount={}",
                    doc.getId(), doc.getStatus(), retryCount);
        } catch (Exception ex) {
            log.warn("Failed to republish unfinished document processing task. docId={}, reason={}",
                    doc.getId(), ex.getMessage());
        }
    }
}
