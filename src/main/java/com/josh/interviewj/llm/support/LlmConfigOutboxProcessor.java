package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmRuntimeProperties;
import com.josh.interviewj.usage.model.LlmConfigChangeOutbox;
import com.josh.interviewj.usage.repository.LlmConfigChangeOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmConfigOutboxProcessor {

    private final LlmConfigChangeOutboxRepository llmConfigChangeOutboxRepository;
    private final LlmConfigInvalidationPublisher llmConfigInvalidationPublisher;
    private final LlmRuntimeProperties llmRuntimeProperties;

    @Scheduled(fixedDelayString = "#{@llmRuntimeProperties.outbox.pollInterval.toMillis()}")
    public void publishPendingChanges() {
        if (!llmRuntimeProperties.getOutbox().isEnabled()) {
            return;
        }

        llmConfigChangeOutboxRepository.findTop100ByPublishStatusOrderByCreatedAtAscIdAsc("PENDING")
                .stream()
                .limit(Math.max(1, llmRuntimeProperties.getOutbox().getBatchSize()))
                .forEach(this::publishOne);
    }

    private void publishOne(LlmConfigChangeOutbox outbox) {
        int nextAttempt = (outbox.getPublishAttempts() == null ? 0 : outbox.getPublishAttempts()) + 1;
        try {
            llmConfigInvalidationPublisher.publishInvalidation(
                    outbox.getConfigVersion(),
                    outbox.getChangeType(),
                    outbox.getPayload()
            );
            outbox.setPublishStatus("PUBLISHED");
            outbox.setPublishAttempts(nextAttempt);
            outbox.setPublishedAt(LocalDateTime.now());
            outbox.setLastError(null);
            llmConfigChangeOutboxRepository.save(outbox);
        } catch (RuntimeException exception) {
            outbox.setPublishAttempts(nextAttempt);
            outbox.setLastError(safeMessage(exception));
            outbox.setPublishStatus(nextAttempt >= Math.max(1, llmRuntimeProperties.getOutbox().getMaxPublishAttempts())
                    ? "FAILED"
                    : "PENDING");
            llmConfigChangeOutboxRepository.save(outbox);
            log.warn(
                    "llm_config_outbox_publish_failed outboxId={} version={} attempts={} message={}",
                    outbox.getId(),
                    outbox.getConfigVersion(),
                    nextAttempt,
                    outbox.getLastError()
            );
        }
    }

    private String safeMessage(RuntimeException exception) {
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return exception.getMessage();
    }
}
