package com.josh.interviewj.common.outbox;

import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPublisherService {

    private static final int MAX_POLL_BATCH_SIZE = 200;

    private final ResumeParseOutboxRepository outboxRepository;
    private final AsyncTaskPublisher asyncTaskPublisher;
    private final ResumeRepository resumeRepository;

    private final String ownerId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${app.outbox.max-retries:5}")
    private int maxOutboxRetries;

    @Value("${app.outbox.processing-timeout:300000}")
    private long processingTimeoutMs;

    /**
     * Poll NEW/RETRY outbox records and publish them into RabbitMQ.
     *
     * <p>This job is idempotent and safe to run concurrently across instances due to
     * CAS claim + owner fencing in the outbox table.</p>
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:5000}")
    public void publishPendingOutboxMessages() {
        List<ResumeParseOutbox> pending = outboxRepository
                .findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY));

        if (pending == null || pending.isEmpty()) {
            return;
        }

        pending.stream().limit(MAX_POLL_BATCH_SIZE).forEach(this::publishOne);
    }

    /**
     * Publish a single outbox record.
     *
     * <p>Flow: claim -> publish to RabbitMQ -> mark SENT; on failure, mark RETRY/FAILED and
     * optionally mark Resume FAILED when outbox retries are exhausted.</p>
     *
     * @param outbox outbox record snapshot
     */
    private void publishOne(ResumeParseOutbox outbox) {
        // 1) Claim the record (CAS) and set owner.
        int claimed = outboxRepository.claimForProcessing(
                outbox.getId(),
                ownerId,
                OutboxStatus.PROCESSING,
                List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
        );
        if (claimed == 0) {
            return;
        }

        log.info("Outbox state changed: outboxId={}, resumeId={}, owner={}, from={}, to={}, eventCode={}, reason={}",
                outbox.getId(), outbox.getResumeId(), ownerId,
                outbox.getStatus() + " (pre_claim_snapshot)", OutboxStatus.PROCESSING,
                "OUTBOX_CLAIMED", "claim_for_publish");

        try {
            AsyncTaskPublisher.PublishResult publishResult = asyncTaskPublisher.publishResumeParseTask(
                    new ResumeParseMessage(outbox.getResumeId(), outbox.getResumeExternalId(), outbox.getId())
            );
            if (!publishResult.published()) {
                throw new IllegalStateException(publishResult.failureReason());
            }

            // 3) Mark the outbox as SENT with owner fencing.
            int updated = outboxRepository.markAsSentWithOwner(
                    outbox.getId(),
                    ownerId,
                    OutboxStatus.SENT,
                    LocalDateTime.now(),
                    OutboxStatus.PROCESSING
            );
            if (updated == 0) {
                log.warn("Outbox owner mismatch, message already published: outboxId={}, owner={}",
                        outbox.getId(), ownerId);
            } else {
                log.info("Outbox state changed: outboxId={}, resumeId={}, owner={}, from={}, to={}, eventCode={}, reason={}",
                        outbox.getId(), outbox.getResumeId(), ownerId,
                        OutboxStatus.PROCESSING, OutboxStatus.SENT,
                        "OUTBOX_PUBLISHED", "publish_success");
            }
        } catch (Exception e) {
            int retryCount = outbox.getRetryCount() + 1;
            if (retryCount >= maxOutboxRetries) {
                // 4a) Exhausted: mark outbox FAILED and also mark Resume FAILED.
                int updated = outboxRepository.markAsFailedWithOwner(
                        outbox.getId(),
                        ownerId,
                        OutboxStatus.FAILED,
                        e.getMessage(),
                        OutboxStatus.PROCESSING
                );
                if (updated > 0) {
                    log.error("Outbox state changed: outboxId={}, resumeId={}, owner={}, from={}, to={}, eventCode={}, reason={}",
                            outbox.getId(), outbox.getResumeId(), ownerId,
                            OutboxStatus.PROCESSING, OutboxStatus.FAILED,
                            "OUTBOX_PUBLISH_FAILED", "publish_exhausted");
                    resumeRepository.markAsFailedFromOutbox(
                            outbox.getResumeId(),
                            "Outbox publish failed: " + e.getMessage(),
                            ResumeStatus.FAILED,
                            List.of(ResumeStatus.PENDING, ResumeStatus.PARSING)
                    );
                }
            } else {
                // 4b) Retryable: move back to RETRY, clear owner.
                outboxRepository.prepareRetryWithOwner(
                        outbox.getId(),
                        ownerId,
                        OutboxStatus.RETRY,
                        retryCount,
                        OutboxStatus.PROCESSING
                );
                log.warn("Outbox state changed: outboxId={}, resumeId={}, owner={}, from={}, to={}, eventCode={}, reason={}",
                        outbox.getId(), outbox.getResumeId(), ownerId,
                        OutboxStatus.PROCESSING, OutboxStatus.RETRY,
                        "OUTBOX_PUBLISH_FAILED", "publish_retry");
            }
        }
    }

    /**
     * Recover outbox records stuck in PROCESSING beyond processing timeout.
     */
    @Scheduled(fixedDelayString = "${app.outbox.recovery-interval:60000}")
    public void recoverTimedOutProcessing() {
        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(processingTimeoutMs));
        int recovered = outboxRepository.recoverTimedOutProcessing(deadline, OutboxStatus.RETRY, OutboxStatus.PROCESSING);
        if (recovered > 0) {
            log.warn("Outbox state changed: count={}, from={}, to={}, eventCode={}, reason={}",
                    recovered, OutboxStatus.PROCESSING, OutboxStatus.RETRY,
                    "OUTBOX_RECOVERED", "timeout_recovery");
        }
    }
}
