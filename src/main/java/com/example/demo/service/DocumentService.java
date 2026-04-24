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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;



@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository docRepo;
    private final DocumentChunkRepository chunkRepo;
    private final RagService ragService;
    private final ChunkSearchRepository chunkSearchRepository;
    private final RabbitTemplate rabbitTemplate; // 注入 RabbitTemplate

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private DocumentRepository documentRepository;


    // Step 1: 上传文档（只存基本信息，不解析）
    public Document saveDocument(Long userId, String title, String filePath) {
        Document doc = new Document();
        doc.setUserId(userId);
        doc.setTitle(title);
        doc.setFilePath(filePath);
        doc.setStatus(DocStatus.PENDING); // 初始状态
        doc = docRepo.save(doc);

        // 发送 MQ 消息
        rabbitTemplate.convertAndSend(RabbitConfig.DOC_QUEUE, doc.getId());

        return doc;
    }

    // Step 2: 异步处理文档（由 Consumer 调用）
    @Transactional
    public void processDocumentAsync(Long docId) {
        // 1. 获取文档记录
        Document doc = docRepo.findById(docId).orElse(null);
        if (doc == null) return;

        try {
            // 更新状态：处理中
            doc.setStatus(DocStatus.PROCESSING);
            docRepo.save(doc);

            // 2. 解析 PDF
            File file = new File(doc.getFilePath());
            PDDocument pdf = PDDocument.load(file);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            pdf.close();

            // 更新文档内容
            doc.setContent(text);

            // 3. 切片
            List<String> chunks = TextSplitter.splitText(text, 800, 200);

            // 4. Embedding 并存入向量库
            saveChunks(doc.getId(), chunks);

            // 5. 更新状态：完成
            doc.setStatus(DocStatus.COMPLETED);
            docRepo.save(doc);

        } catch (Exception e) {
            e.printStackTrace();
            doc.setStatus(DocStatus.FAILED);
            docRepo.save(doc);
        }
    }


    // 保存chunk并生成 chunk embedding
    public void saveChunks(Long docId, List<String> chunks) {

        int index = 0;
        for (String text : chunks) {

            if (text.strip().length() == 0) continue; // 跳过空文本
            // 1) 创建 chunk
            DocumentChunk c = new DocumentChunk();
            c.setDocumentId(docId);
            c.setChunkIndex(index++);
            c.setText(text);
            chunkRepo.save(c);
            // 2) 生成 chunk 的 embedding
            List<Double> vec = ragService.embedding(text);
            // 3) 写入 embedding JSON
            c.setEmbeddingVector(vec);
            // 4) 数据库存储
            chunkRepo.save(c);

            // OLD: 旧逻辑到此结束（仅写入 PG + pgvector）
            // NEW: 新增写入 Elasticsearch，支持 BM25 召回
            ChunkSearchDoc searchDoc = new ChunkSearchDoc();
            searchDoc.setId(String.valueOf(c.getId()));
            searchDoc.setChunkId(c.getId());
            searchDoc.setDocumentId(docId);
            searchDoc.setChunkIndex(c.getChunkIndex());
            searchDoc.setText(c.getText());
            searchDoc.setUpdatedAt(Instant.now());
            chunkSearchRepository.save(searchDoc);
        }
    }


    //显示所有文档
    public List<Document> listDocs(Long userId) {
        return docRepo.findByUserId(userId);
    }


    /*
     * 事务性删除文档、相关的对话和聊天记录，以及本地文件。
     * 先删除chat_massage、再删除conservative、再删除document，这样才不会报错！
     */
    @Transactional
    public void deleteDocumentWithRelatedData(Long docId, Long userId) {


        // --- 1. 查找 Document 记录 ---
        // OLD: 仅按 docId 查找，缺少用户归属校验
        // Document document = documentRepository.findById(docId)
        //         .orElseThrow(() -> new RuntimeException("文档不存在或已被删除！ID: " + docId));
        Document document = documentRepository.findByIdAndUserId(docId, userId)
                .orElseThrow(() -> new RuntimeException("文档不存在、已被删除或无访问权限！ID: " + docId));

        // 获取文件路径用于本地删除
        String filePath = document.getFilePath();

        // --- 2. 查找相关的 Conversation (0或1个) ---
        String expectedTitle = "Doc-" + docId + " 对话";
        Optional<Conversation> conversationOpt = conversationRepository.findByTitle(expectedTitle);

        if (conversationOpt.isPresent()) {
            Conversation conversation = conversationOpt.get();
            //获取对话的id
            Long conversationId = conversation.getId();

            // --- 3. 删除相关的 Chat_Message ---
            // 批量删除该对话下的所有聊天记录
            chatMessageRepository.deleteByConversationId(conversationId);

            // --- 4. 删除 Conversation ---
            conversationRepository.delete(conversation);
        }
        // 如果 conversationOpt.isEmpty()，则跳过步骤 3 和 4，因为没有关联的对话。



        // --- 5. 删除关联的 DocumentChunk 记录
        // ----------------------------------------------------
        chunkRepo.deleteByDocumentId(docId);
        chunkSearchRepository.deleteByDocumentId(docId);
        // ----------------------------------------------------

        // --- 6. 删除 Document 记录 ---
        documentRepository.delete(document);

        // --- 7. 删除本地文件 ---
        deleteLocalDocumentFile(filePath);
    }

    /*
     * 删除服务器上的本地文件。
     */
    private void deleteLocalDocumentFile(String filePath) {
        File file = new File(filePath);

        if (file.exists()) {
            if (!file.delete()) {
                // 建议记录日志，但通常不抛出异常中断事务（除非文件删除是核心业务）
                System.err.println("本地文件删除失败！路径: " + file.getAbsolutePath());
            }
        }
    }



}