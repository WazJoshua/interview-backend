package com.josh.interviewj.common.job;

import com.josh.interviewj.interview.service.InterviewTimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.interview.timeout.enabled", havingValue = "true", matchIfMissing = true)
public class InterviewTimeoutJob {

    private final InterviewTimeoutService interviewTimeoutService;

    @Value("${app.interview.timeout.enabled:true}")
    private boolean enabled;

    @Value("${app.interview.timeout.batch-size:50}")
    private int batchSize;

    @EventListener(ApplicationReadyEvent.class)
    public void drainOnStartup() {
        if (!enabled) {
            return;
        }
        interviewTimeoutService.abortTimedOutSessions(PageRequest.of(0, batchSize), null);
    }

    @Scheduled(fixedDelayString = "${app.interview.timeout.poll-interval:60000}")
    public void drainBacklog() {
        if (!enabled) {
            return;
        }
        InterviewTimeoutService.AbortTimedOutResult result =
                interviewTimeoutService.abortTimedOutSessions(PageRequest.of(0, batchSize), null);
        if (result.scannedCount() > 0
                || result.abortedCount() > 0
                || result.skippedCount() > 0
                || result.failureCount() > 0) {
            log.info("Interview timeout backlog scan finished: scannedCount={}, abortedCount={}, skippedCount={}, failureCount={}",
                    result.scannedCount(), result.abortedCount(), result.skippedCount(), result.failureCount());
        }
    }
}
