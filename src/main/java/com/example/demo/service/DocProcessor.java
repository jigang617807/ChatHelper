package com.example.demo.service;

import com.example.demo.config.RabbitConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocProcessor {

    private final DocumentService docService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${document.processing.max-retries:3}")
    private int maxRetries;

    @RabbitListener(queues = RabbitConfig.DOC_QUEUE)
    public void processDoc(Long docId, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retryCount = retryCount(message);

        try {
            log.info("Processing document task. docId={}, retryCount={}", docId, retryCount);
            docService.processDocumentAsync(docId, message.getMessageProperties().isRedelivered());
            channel.basicAck(deliveryTag, false);
        } catch (RetryableDocumentProcessingException ex) {
            handleRetryableFailure(docId, retryCount, deliveryTag, channel, ex);
        } catch (NonRetryableDocumentProcessingException ex) {
            log.warn("Non-retryable document task failed. docId={}, reason={}", docId, ex.getMessage());
            docService.markFailed(docId, ex.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            handleRetryableFailure(docId, retryCount, deliveryTag, channel, ex);
        }
    }

    private void handleRetryableFailure(Long docId,
                                        int retryCount,
                                        long deliveryTag,
                                        Channel channel,
                                        Exception ex) throws IOException {
        if (retryCount >= maxRetries) {
            log.error("Document task exceeded max retries. docId={}, retryCount={}", docId, retryCount, ex);
            docService.markFailed(docId, "Exceeded max retries. Last error: " + ex.getMessage());
            publishDeadLetter(docId, retryCount, ex);
            channel.basicAck(deliveryTag, false);
            return;
        }

        int nextRetry = retryCount + 1;
        log.warn("Document task will retry. docId={}, nextRetry={}, reason={}", docId, nextRetry, ex.getMessage());
        try {
            publishRetry(docId, nextRetry);
            channel.basicAck(deliveryTag, false);
        } catch (Exception publishError) {
            log.error("Failed to publish retry message. Requeue original message. docId={}", docId, publishError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void publishRetry(Long docId, int retryCount) {
        String correlationId = "doc-process-retry-" + docId + "-" + retryCount;
        rabbitTemplate.convertAndSend(
                RabbitConfig.DOC_RETRY_EXCHANGE,
                RabbitConfig.DOC_RETRY_ROUTING_KEY,
                docId,
                msg -> {
                    msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    msg.getMessageProperties().setHeader("docId", docId);
                    msg.getMessageProperties().setHeader("retryCount", retryCount);
                    msg.getMessageProperties().setCorrelationId(correlationId);
                    return msg;
                },
                new CorrelationData(correlationId)
        );
    }

    private void publishDeadLetter(Long docId, int retryCount, Exception ex) {
        String correlationId = "doc-process-dead-" + docId + "-" + retryCount;
        rabbitTemplate.convertAndSend(
                RabbitConfig.DOC_DEAD_EXCHANGE,
                RabbitConfig.DOC_DEAD_ROUTING_KEY,
                docId,
                msg -> {
                    msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    msg.getMessageProperties().setHeader("docId", docId);
                    msg.getMessageProperties().setHeader("retryCount", retryCount);
                    msg.getMessageProperties().setHeader("errorMessage", ex.getMessage());
                    msg.getMessageProperties().setCorrelationId(correlationId);
                    return msg;
                },
                new CorrelationData(correlationId)
        );
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("retryCount");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
