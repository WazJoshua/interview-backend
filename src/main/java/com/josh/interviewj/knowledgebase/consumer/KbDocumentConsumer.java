package com.josh.interviewj.knowledgebase.consumer;

import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KbDocumentIngestionService;
import com.josh.interviewj.knowledgebase.service.KbDocumentRetryService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes KB document ingestion tasks from RabbitMQ with idempotency and retry control.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KbDocumentConsumer {

    private static final String IDEMPOTENT_KEY_PREFIX = "kb:doc:processed:";

    private final StringRedisTemplate stringRedisTemplate;
    private final KbDocumentRepository kbDocumentRepository;
    private final KbDocumentOutboxRepository kbDocumentOutboxRepository;
    private final KbDocumentIngestionService kbDocumentIngestionService;
    private final KnowledgeBaseReindexService knowledgeBaseReindexService;
    private final IngestionFailureClassifier ingestionFailureClassifier;
    private final KbDocumentRetryService kbDocumentRetryService;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService virtualThreadExecutor;

    @Value("${app.mq.kb-doc.idempotent-cache-ttl:${app.redis-stream.kb-doc.idempotent-cache-ttl:7200000}}")
    private long idempotentCacheTtlMs;

    @Value("${app.mq.kb-doc.message-timeout:${app.redis-stream.kb-doc.message-timeout:600000}}")
    private long messageTimeoutMs;

    @Value("${app.mq.kb-doc.heartbeat-interval:${app.redis-stream.kb-doc.heartbeat-interval:30000}}")
    private long heartbeatIntervalMs;

    @RabbitListener(queues = "${app.mq.kb-doc.queue}", containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(
            KbDocumentMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        if (message == null
                || message.kbId() == null
                || message.kbExternalId() == null
                || message.documentId() == null
                || message.documentExternalId() == null
                || message.outboxId() == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Long outboxId = message.outboxId();
        if (isAlreadyProcessed(outboxId)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Long documentId = message.documentId();
        KbDocument document = kbDocumentRepository.findById(documentId).orElse(null);
        if (document == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(messageTimeoutMs));
        int claimed = kbDocumentRepository.claimForProcessing(documentId, deadline, KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING);
        if (claimed == 0) {
            if (kbDocumentOutboxRepository.existsByRetrySourceOutboxId(outboxId)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            handleClaimRejected(documentId, channel, deliveryTag);
            return;
        }

        ScheduledFuture<?> heartbeatFuture = startHeartbeat(documentId);
        try {
            kbDocumentIngestionService.ingestAndFinalize(documentId, outboxId, idempotentCacheTtlMs);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            handleFailure(message, channel, deliveryTag, exception);
        } finally {
            heartbeatFuture.cancel(false);
        }
    }

    private void handleFailure(KbDocumentMessage message, Channel channel, long deliveryTag, Exception exception) throws IOException {
        KbDocument document = kbDocumentRepository.findById(message.documentId()).orElse(null);
        if (document == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        IngestionFailure failure = ingestionFailureClassifier.classify(exception);
        if (failure.category() == IngestionFailureCategory.INFRA_RETRYABLE) {
            KbDocumentRetryService.RetrySchedulingResult result = kbDocumentRetryService.scheduleRetry(
                    message.documentId(),
                    message.outboxId(),
                    failure.safeMessage()
            );
            if (result == KbDocumentRetryService.RetrySchedulingResult.SCHEDULED
                    || result == KbDocumentRetryService.RetrySchedulingResult.ALREADY_SCHEDULED
                    || result == KbDocumentRetryService.RetrySchedulingResult.EXHAUSTED) {
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        kbDocumentRepository.markFailed(message.documentId(), failure.safeMessage(), KbDocumentStatus.FAILED, KbDocumentStatus.PROCESSING);
        knowledgeBaseReindexService.maybeCompleteReindex(document.getKbId());
        channel.basicNack(deliveryTag, false, false);
    }

    private void handleClaimRejected(Long documentId, Channel channel, long deliveryTag) throws IOException {
        KbDocument current = kbDocumentRepository.findById(documentId).orElse(null);
        if (current == null
                || current.getStatus() == KbDocumentStatus.COMPLETED
                || current.getStatus() == KbDocumentStatus.FAILED) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        channel.basicNack(deliveryTag, false, true);
    }

    private ScheduledFuture<?> startHeartbeat(Long documentId) {
        AtomicBoolean running = new AtomicBoolean(false);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            virtualThreadExecutor.submit(() -> {
                try {
                    kbDocumentRepository.heartbeat(documentId, KbDocumentStatus.PROCESSING);
                } finally {
                    running.set(false);
                }
            });
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean isAlreadyProcessed(Long outboxId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(IDEMPOTENT_KEY_PREFIX + outboxId));
    }
}
