package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Publishes KB document outbox rows into Redis Stream for asynchronous ingestion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KbDocumentOutboxPublisherService {

    private static final int MAX_POLL_BATCH_SIZE = 200;

    private final KbDocumentOutboxRepository outboxRepository;
    private final KbDocumentRepository kbDocumentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AsyncTaskPublisher asyncTaskPublisher;

    private final String ownerId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${app.outbox.max-retries:5}")
    private int maxOutboxRetries;

    /**
     * Polls pending KB document outbox rows and pushes them into Redis Stream.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:5000}")
    public void publishPendingOutboxMessages() {
        List<KbDocumentOutbox> pending = outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        pending.stream().limit(MAX_POLL_BATCH_SIZE).forEach(this::publishOne);
    }

    /**
     * Claims and publishes a single outbox row.
     *
     * @param outbox outbox row to publish
     */
    private void publishOne(KbDocumentOutbox outbox) {
        int claimed = outboxRepository.claimForProcessing(outbox.getId(), ownerId, OutboxStatus.PROCESSING, List.of(OutboxStatus.NEW, OutboxStatus.RETRY));
        if (claimed == 0) {
            return;
        }

        try {
            // Load both document and knowledge base so the stream message contains all identifiers needed downstream.
            KbDocument document = kbDocumentRepository.findById(outbox.getDocumentId()).orElse(null);
            KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(outbox.getKbId()).orElse(null);
            if (document == null || knowledgeBase == null) {
                outboxRepository.markAsFailedWithOwner(outbox.getId(), ownerId, OutboxStatus.FAILED, "KB document or knowledge base not found", OutboxStatus.PROCESSING);
                return;
            }

            AsyncTaskPublisher.PublishResult publishResult = asyncTaskPublisher.publishKbDocumentTask(
                    new KbDocumentMessage(
                            knowledgeBase.getId(),
                            knowledgeBase.getExternalId(),
                            document.getId(),
                            document.getExternalId(),
                            outbox.getId()
                    )
            );
            if (!publishResult.published()) {
                throw new IllegalStateException(publishResult.failureReason());
            }
            outboxRepository.markAsSentWithOwner(outbox.getId(), ownerId, OutboxStatus.SENT, LocalDateTime.now(), OutboxStatus.PROCESSING);
        } catch (Exception e) {
            int retryCount = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
            // Convert transient publish failures into retry state until the retry budget is exhausted.
            if (retryCount >= maxOutboxRetries) {
                outboxRepository.markAsFailedWithOwner(outbox.getId(), ownerId, OutboxStatus.FAILED, e.getMessage(), OutboxStatus.PROCESSING);
                log.error("KB outbox publish failed permanently: outboxId={}, error={}", outbox.getId(), e.getMessage());
            } else {
                outboxRepository.prepareRetryWithOwner(outbox.getId(), ownerId, OutboxStatus.RETRY, retryCount, OutboxStatus.PROCESSING);
                log.warn("KB outbox publish failed, prepare retry: outboxId={}, retryCount={}, error={}", outbox.getId(), retryCount, e.getMessage());
            }
        }
    }
}
