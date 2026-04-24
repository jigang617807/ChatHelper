package com.example.demo.service;

import com.example.demo.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocProcessor {

    private final DocumentService docService;

    // 监听队列，收到 docId 后异步处理
    @RabbitListener(queues = RabbitConfig.DOC_QUEUE)
    public void processDoc(Long docId) {
        log.info("收到文档处理任务: docId={}", docId);
        try {
            docService.processDocumentAsync(docId);
        } catch (Exception e) {
            log.error("处理文档失败: docId={}", docId, e);
            // 这里可以加重试机制，但目前先不加
        }
    }
}