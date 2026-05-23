package com.example.demo.service;

import com.example.demo.config.RabbitConfig;
import com.example.demo.entity.Conversation;
import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.entity.DocumentChunk;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ConversationRepository;
import com.example.demo.repository.DocumentChunkRepository;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.search.ChunkSearchDoc;
import com.example.demo.search.ChunkSearchRepository;
import com.example.demo.utils.TextSplitter;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository docRepo;
    private final DocumentChunkRepository chunkRepo;
    private final RagService ragService;
    private final ChunkSearchRepository chunkSearchRepository;
    private final RagRetrievalCacheService ragRetrievalCacheService;
    private final RabbitTemplate rabbitTemplate;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final DocumentRepository documentRepository;

    @Value("${document.processing.stale-processing-minutes:30}")
    private long staleProcessingMinutes;

    public Document saveDocument(Long userId, String title, String filePath) {
        Document doc = new Document();
        doc.setUserId(userId);
        doc.setTitle(title);
        doc.setFilePath(filePath);
        doc.setStatus(DocStatus.PENDING);
        doc.setRetryCount(0);
        doc = docRepo.save(doc);

        sendProcessMessage(doc.getId(), 0);
        return doc;
    }

    public void sendProcessMessage(Long docId, int retryCount) {
        String correlationId = "doc-process-" + docId + "-" + retryCount;
        rabbitTemplate.convertAndSend(
                RabbitConfig.DOC_EXCHANGE,
                RabbitConfig.DOC_ROUTING_KEY,
                docId,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setHeader("docId", docId);
                    message.getMessageProperties().setHeader("retryCount", retryCount);
                    message.getMessageProperties().setCorrelationId(correlationId);
                    return message;
                },
                new CorrelationData(correlationId)
        );
    }

    @Transactional(dontRollbackOn = DocumentProcessingException.class)
    public void processDocumentAsync(Long docId) {
        processDocumentAsync(docId, false);
    }

    @Transactional(dontRollbackOn = DocumentProcessingException.class)
    public void processDocumentAsync(Long docId, boolean redelivered) {
        Document before = docRepo.findById(docId)
                .orElseThrow(() -> new NonRetryableDocumentProcessingException("Document does not exist. id=" + docId));

        if (before.getStatus() == DocStatus.COMPLETED) {
            return;
        }
        if (before.getStatus() == DocStatus.PROCESSING && !redelivered && !isStaleProcessing(before)) {
            return;
        }

        List<DocStatus> allowedStatuses = allowedStatuses(before, redelivered);
        int locked = docRepo.markProcessing(docId, allowedStatuses, DocStatus.PROCESSING, LocalDateTime.now());
        if (locked == 0) {
            return;
        }

        Document doc = docRepo.findById(docId)
                .orElseThrow(() -> new NonRetryableDocumentProcessingException("Document does not exist. id=" + docId));

        try {
            rebuildIndexes(doc);
            doc.setStatus(DocStatus.COMPLETED);
            doc.setErrorMessage(null);
            doc.setProcessedAt(LocalDateTime.now());
            docRepo.save(doc);
        } catch (NonRetryableDocumentProcessingException ex) {
            markFailed(docId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            markRetryableFailure(docId, ex.getMessage());
            throw new RetryableDocumentProcessingException("Retryable document processing failure. docId=" + docId, ex);
        }
    }

    private void rebuildIndexes(Document doc) {
        Long docId = doc.getId();
        cleanupDocumentIndexes(doc.getUserId(), docId);

        File file = new File(doc.getFilePath());
        if (!file.exists() || !file.isFile()) {
            throw new NonRetryableDocumentProcessingException("PDF file does not exist: " + file.getAbsolutePath());
        }

        String text = extractPdfText(file);
        if (text == null || text.isBlank()) {
            throw new NonRetryableDocumentProcessingException("PDF text is empty. docId=" + docId);
        }

        doc.setContent(text);
        docRepo.save(doc);

        List<String> chunks = TextSplitter.splitText(text, 800, 200);
        saveChunks(docId, chunks);
    }

    private String extractPdfText(File file) {
        try (PDDocument pdf = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(pdf);
        } catch (Exception ex) {
            throw new NonRetryableDocumentProcessingException("PDF parse failed: " + ex.getMessage(), ex);
        }
    }

    private void cleanupDocumentIndexes(Long userId, Long docId) {
        chunkRepo.deleteByDocumentId(docId);
        chunkSearchRepository.deleteByDocumentId(docId);
        ragRetrievalCacheService.evictDocument(userId, docId);
    }

    public void saveChunks(Long docId, List<String> chunks) {
        int index = 0;
        for (String text : chunks) {
            if (text == null || text.strip().isEmpty()) {
                continue;
            }

            int chunkIndex = index++;
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(docId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setText(text);
            chunk = chunkRepo.save(chunk);

            List<Double> vector = ragService.embedding(text);
            chunk.setEmbeddingVector(vector);
            chunk = chunkRepo.save(chunk);

            ChunkSearchDoc searchDoc = new ChunkSearchDoc();
            searchDoc.setId(docId + "_" + chunkIndex);
            searchDoc.setChunkId(chunk.getId());
            searchDoc.setDocumentId(docId);
            searchDoc.setChunkIndex(chunkIndex);
            searchDoc.setText(chunk.getText());
            searchDoc.setUpdatedAt(Instant.now());
            chunkSearchRepository.save(searchDoc);
        }
    }

    public List<Document> listDocs(Long userId) {
        return docRepo.findByUserId(userId);
    }

    public void markRetryableFailure(Long docId, String message) {
        docRepo.findById(docId).ifPresent(doc -> {
            doc.setStatus(DocStatus.FAILED_RETRYABLE);
            doc.setRetryCount(nullToZero(doc.getRetryCount()) + 1);
            doc.setErrorMessage(limitMessage(message));
            docRepo.save(doc);
        });
    }

    public void markFailed(Long docId, String message) {
        docRepo.findById(docId).ifPresent(doc -> {
            doc.setStatus(DocStatus.FAILED);
            doc.setErrorMessage(limitMessage(message));
            doc.setProcessedAt(LocalDateTime.now());
            docRepo.save(doc);
        });
    }

    @Transactional
    public void deleteDocumentWithRelatedData(Long docId, Long userId) {
        Document document = documentRepository.findByIdAndUserId(docId, userId)
                .orElseThrow(() -> new RuntimeException("文档不存在、已删除或无访问权限。ID: " + docId));

        String filePath = document.getFilePath();

        deleteConversation("Doc-" + docId + " 对话");
        deleteConversation("Agent-Doc-" + docId);

        cleanupDocumentIndexes(userId, docId);
        documentRepository.delete(document);
        deleteLocalDocumentFile(filePath);
    }

    private void deleteConversation(String title) {
        Optional<Conversation> conversationOpt = conversationRepository.findByTitle(title);
        if (conversationOpt.isEmpty()) {
            return;
        }
        Conversation conversation = conversationOpt.get();
        chatMessageRepository.deleteByConversationId(conversation.getId());
        conversationRepository.delete(conversation);
    }

    private void deleteLocalDocumentFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        File file = new File(filePath);
        if (file.exists() && !file.delete()) {
            System.err.println("Failed to delete local document file: " + file.getAbsolutePath());
        }
    }

    private boolean isStaleProcessing(Document doc) {
        if (doc.getProcessingStartedAt() == null) {
            return true;
        }
        long minutes = Math.max(1, staleProcessingMinutes);
        return doc.getProcessingStartedAt().plus(Duration.ofMinutes(minutes)).isBefore(LocalDateTime.now());
    }

    private List<DocStatus> allowedStatuses(Document doc, boolean redelivered) {
        if (doc.getStatus() == DocStatus.PROCESSING && (redelivered || isStaleProcessing(doc))) {
            return List.of(DocStatus.PENDING, DocStatus.FAILED_RETRYABLE, DocStatus.PROCESSING);
        }
        return List.of(DocStatus.PENDING, DocStatus.FAILED_RETRYABLE);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String limitMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000) + "\n[TRUNCATED]";
    }
}
