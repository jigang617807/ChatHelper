package com.example.demo.service;

import com.example.demo.config.RabbitConfig;
import com.example.demo.entity.ChunkContentType;
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
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
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
    private final DocumentParsingService documentParsingService;
    private final TransactionTemplate transactionTemplate;

    @Value("${document.processing.stale-processing-minutes:30}")
    private long staleProcessingMinutes;

    @Value("${upload.dir:.}")
    private String uploadDir;

    @Value("${upload.doc-media:uploads/doc-media/}")
    private String docMediaDir;

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

    public void processDocumentAsync(Long docId) {
        processDocumentAsync(docId, false);
    }

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
        if (!markProcessingCommitted(docId, allowedStatuses)) {
            return;
        }

        try {
            processDocumentBodyCommitted(docId);
        } catch (NonRetryableDocumentProcessingException ex) {
            markFailed(docId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            markRetryableFailure(docId, ex.getMessage());
            throw new RetryableDocumentProcessingException("Retryable document processing failure. docId=" + docId, ex);
        }
    }

    private void processDocumentBodyCommitted(Long docId) {
        transactionTemplate.executeWithoutResult(status -> {
            Document doc = docRepo.findById(docId)
                    .orElseThrow(() -> new NonRetryableDocumentProcessingException("Document does not exist. id=" + docId));
            rebuildIndexes(doc);
            doc.setStatus(DocStatus.COMPLETED);
            doc.setErrorMessage(null);
            doc.setProcessedAt(LocalDateTime.now());
            docRepo.save(doc);
        });
    }

    private boolean markProcessingCommitted(Long docId, List<DocStatus> allowedStatuses) {
        Boolean locked = transactionTemplate.execute(status ->
                docRepo.markProcessing(docId, allowedStatuses, DocStatus.PROCESSING, LocalDateTime.now()) > 0
        );
        return Boolean.TRUE.equals(locked);
    }

    private void rebuildIndexes(Document doc) {
        Long docId = doc.getId();
        cleanupDocumentIndexes(doc.getUserId(), docId);

        File file = new File(doc.getFilePath());
        if (!file.exists() || !file.isFile()) {
            throw new NonRetryableDocumentProcessingException("PDF file does not exist: " + file.getAbsolutePath());
        }

        Path mediaDir = Path.of(new File(uploadDir).getAbsolutePath(), docMediaDir, "doc-" + docId);
        String mediaWebPrefix = "/" + docMediaDir.replace("\\", "/") + "doc-" + docId + "/";
        ParsedDocument parsedDocument = documentParsingService.parsePdf(file, mediaDir, mediaWebPrefix);
        String text = parsedDocument.fullText();

        doc.setContent(text);
        docRepo.save(doc);

        saveChunks(docId, parsedDocument.chunks());
    }

    private void cleanupDocumentIndexes(Long userId, Long docId) {
        chunkRepo.deleteByDocumentId(docId);
        chunkSearchRepository.deleteByDocumentId(docId);
        ragRetrievalCacheService.evictDocument(userId, docId);
    }

    public void saveChunks(Long docId, List<ParsedChunk> chunks) {
        int index = 0;
        for (ParsedChunk parsedChunk : chunks) {
            String text = parsedChunk == null ? null : parsedChunk.text();
            if (text == null || text.strip().isEmpty()) {
                continue;
            }

            int chunkIndex = index++;
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(docId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setPageNumber(parsedChunk.pageNumber());
            chunk.setSectionTitle(parsedChunk.sectionTitle());
            chunk.setContentType(parsedChunk.contentType() == null ? ChunkContentType.TEXT.name() : parsedChunk.contentType().name());
            chunk.setSourcePath(parsedChunk.sourcePath());
            chunk.setMetadata(parsedChunk.metadata());
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
            searchDoc.setPageNumber(chunk.getPageNumber());
            searchDoc.setSectionTitle(chunk.getSectionTitle());
            searchDoc.setContentType(chunk.getContentType() == null ? ChunkContentType.TEXT.name() : chunk.getContentType());
            searchDoc.setSourcePath(chunk.getSourcePath());
            searchDoc.setText(chunk.getText());
            searchDoc.setUpdatedAt(Instant.now());
            chunkSearchRepository.save(searchDoc);
        }
    }

    public List<Document> listDocs(Long userId) {
        return docRepo.findByUserId(userId);
    }

    public void markRetryableFailure(Long docId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            docRepo.findById(docId).ifPresent(doc -> {
                doc.setStatus(DocStatus.FAILED_RETRYABLE);
                doc.setRetryCount(nullToZero(doc.getRetryCount()) + 1);
                doc.setErrorMessage(limitMessage(message));
                docRepo.save(doc);
            });
        });
    }

    public void markFailed(Long docId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            docRepo.findById(docId).ifPresent(doc -> {
                doc.setStatus(DocStatus.FAILED);
                doc.setErrorMessage(limitMessage(message));
                doc.setProcessedAt(LocalDateTime.now());
                docRepo.save(doc);
            });
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
        deleteDocumentMediaDirectory(docId);
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

    private void deleteDocumentMediaDirectory(Long docId) {
        if (docId == null) {
            return;
        }
        try {
            Path mediaRoot = Path.of(new File(uploadDir).getAbsolutePath(), docMediaDir).normalize().toAbsolutePath();
            Path docMediaPath = mediaRoot.resolve("doc-" + docId).normalize().toAbsolutePath();
            if (!docMediaPath.startsWith(mediaRoot) || !java.nio.file.Files.exists(docMediaPath)) {
                return;
            }
            try (var paths = java.nio.file.Files.walk(docMediaPath)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                java.nio.file.Files.deleteIfExists(path);
                            } catch (IOException ex) {
                                System.err.println("Failed to delete document media path: " + path + ", reason=" + ex.getMessage());
                            }
                        });
            }
        } catch (IOException ex) {
            System.err.println("Failed to delete document media directory for docId=" + docId + ", reason=" + ex.getMessage());
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
