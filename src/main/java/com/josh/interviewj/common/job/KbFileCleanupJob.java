package com.josh.interviewj.common.job;

import com.josh.interviewj.knowledgebase.service.KbFileCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers knowledge base file cleanup drains on startup and on a fixed schedule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KbFileCleanupJob {

    private final KbFileCleanupService kbFileCleanupService;

    @Value("${app.kb.cleanup.batch-size:50}")
    private int batchSize;

    /**
     * Drains pending cleanup work once after the application becomes ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void drainOnStartup() {
        kbFileCleanupService.drainReadyTasks(PageRequest.of(0, batchSize));
    }

    /**
     * Scans and drains cleanup backlog in bounded batches.
     */
    @Scheduled(fixedDelayString = "${app.kb.cleanup.interval:60000}")
    public void drainBacklog() {
        KbFileCleanupService.DrainResult result = kbFileCleanupService.drainReadyTasks(PageRequest.of(0, batchSize));
        if (result.successCount() > 0 || result.failureCount() > 0 || result.backlog() > 0) {
            log.info("KB cleanup backlog scan finished: successCount={}, failureCount={}, backlog={}",
                    result.successCount(), result.failureCount(), result.backlog());
        }
    }
}
