package com.josh.interviewj.common.job;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxCleanupJob {

    private final ResumeParseOutboxRepository outboxRepository;

    @Value("${app.outbox.cleanup-retention-days:7}")
    private int outboxRetentionDays;

    /**
     * Delete SENT outbox records older than retention days.
     */
    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval:3600000}")
    public void cleanupSentOutboxRecords() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(outboxRetentionDays);
        int deleted = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} sent outbox records", deleted);
        }
    }
}
