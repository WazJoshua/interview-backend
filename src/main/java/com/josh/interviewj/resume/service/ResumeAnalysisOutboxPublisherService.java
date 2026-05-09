package com.josh.interviewj.resume.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Publishes resume analysis outbox rows into Redis Stream.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResumeAnalysisOutboxPublisherService {

    private static final int MAX_POLL_BATCH_SIZE = 200;

    private final ResumeAnalysisOutboxRepository outboxRepository;
    private final AsyncTaskPublisher asyncTaskPublisher;

    private final String ownerId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${app.outbox.max-retries:5}")
    private int maxOutboxRetries;

    @Value("${app.outbox.processing-timeout:300000}")
    private long processingTimeoutMs;

    /**
     * Polls NEW and RETRY outbox rows and publishes them to the analysis stream.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:5000}")
    public void publishPendingOutboxMessages() {
        List<ResumeAnalysisOutbox> pending = outboxRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
        );
        if (pending == null || pending.isEmpty()) {
            return;
        }

        pending.stream().limit(MAX_POLL_BATCH_SIZE).forEach(this::publishOne);
    }

    /**
     * Recovers timed-out outbox rows stuck in PROCESSING.
     */
    @Scheduled(fixedDelayString = "${app.outbox.recovery-interval:60000}")
    public void recoverTimedOutProcessing() {
        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(processingTimeoutMs));
        int recovered = outboxRepository.recoverTimedOutProcessing(deadline, OutboxStatus.RETRY, OutboxStatus.PROCESSING);
        if (recovered > 0) {
            log.warn("Resume analysis outbox recovered: count={}, from={}, to={}",
                    recovered, OutboxStatus.PROCESSING, OutboxStatus.RETRY);
        }
    }

    /**
     * Claims and publishes a single outbox row.
     *
     * @param outbox outbox snapshot
     */
    private void publishOne(ResumeAnalysisOutbox outbox) {
        int claimed = outboxRepository.claimForProcessing(
                outbox.getId(),
                ownerId,
                OutboxStatus.PROCESSING,
                List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
        );
        if (claimed == 0) {
            return;
        }

        try {
            AsyncTaskPublisher.PublishResult publishResult = asyncTaskPublisher.publishResumeAnalysisTask(
                    new ResumeAnalysisMessage(
                            outbox.getReportId(),
                            outbox.getResumeId(),
                            outbox.getResumeExternalId(),
                            outbox.getId()
                    )
            );
            if (!publishResult.published()) {
                throw new IllegalStateException(publishResult.failureReason());
            }
            outboxRepository.markAsSentWithOwner(
                    outbox.getId(),
                    ownerId,
                    OutboxStatus.SENT,
                    LocalDateTime.now(),
                    OutboxStatus.PROCESSING
            );
        } catch (Exception e) {
            String errorMessage = ResumeAnalysisService.safeErrorMessage(e);
            int retryCount = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
            if (retryCount >= maxOutboxRetries) {
                outboxRepository.markAsFailedWithOwner(
                        outbox.getId(),
                        ownerId,
                        OutboxStatus.FAILED,
                        errorMessage,
                        OutboxStatus.PROCESSING
                );
                log.error("Resume analysis outbox publish failed permanently: outboxId={}, reportId={}, error={}",
                        outbox.getId(), outbox.getReportId(), errorMessage);
                return;
            }

            outboxRepository.prepareRetryWithOwner(
                    outbox.getId(),
                    ownerId,
                    OutboxStatus.RETRY,
                    retryCount,
                    OutboxStatus.PROCESSING
            );
            log.warn("Resume analysis outbox publish failed, prepare retry: outboxId={}, reportId={}, retryCount={}, error={}",
                    outbox.getId(), outbox.getReportId(), retryCount, errorMessage);
        }
    }
}
