package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KbDocumentRetryService {

    private final KbDocumentRepository kbDocumentRepository;
    private final KbDocumentOutboxRepository outboxRepository;
    private final KnowledgeBaseReindexService knowledgeBaseReindexService;

    @Value("${app.mq.kb-doc.max-retries:${app.redis-stream.kb-doc.max-retries:3}}")
    private int maxRetries;

    @Transactional
    public RetrySchedulingResult scheduleRetry(Long documentId, Long sourceOutboxId, String errorMessage) {
        if (outboxRepository.existsByRetrySourceOutboxId(sourceOutboxId)) {
            return RetrySchedulingResult.ALREADY_SCHEDULED;
        }

        int updated = kbDocumentRepository.markPendingForRetry(
                documentId,
                errorMessage,
                KbDocumentStatus.PENDING,
                KbDocumentStatus.PROCESSING
        );
        if (updated == 0) {
            return RetrySchedulingResult.SKIPPED;
        }

        KbDocument document = kbDocumentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return RetrySchedulingResult.SKIPPED;
        }

        long totalAttempts = outboxRepository.countByDocumentId(documentId);
        long retryAttemptsSoFar = Math.max(0L, totalAttempts - 1L);
        if (retryAttemptsSoFar >= maxRetries) {
            kbDocumentRepository.markFailed(documentId, errorMessage, KbDocumentStatus.FAILED, KbDocumentStatus.PENDING);
            knowledgeBaseReindexService.maybeCompleteReindex(document.getKbId());
            return RetrySchedulingResult.EXHAUSTED;
        }

        outboxRepository.save(KbDocumentOutbox.builder()
                .kbId(document.getKbId())
                .documentId(document.getId())
                .status(OutboxStatus.NEW)
                .retryCount((int) retryAttemptsSoFar + 1)
                .retrySourceOutboxId(sourceOutboxId)
                .errorMessage(errorMessage)
                .build());
        return RetrySchedulingResult.SCHEDULED;
    }

    public enum RetrySchedulingResult {
        SCHEDULED,
        ALREADY_SCHEDULED,
        EXHAUSTED,
        SKIPPED
    }
}
