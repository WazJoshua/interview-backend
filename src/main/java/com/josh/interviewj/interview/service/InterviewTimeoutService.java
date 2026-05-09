package com.josh.interviewj.interview.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Slf4j
public class InterviewTimeoutService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewLifecycleService interviewLifecycleService;
    private final long idleThresholdMillis;

    public InterviewTimeoutService(
            InterviewSessionRepository interviewSessionRepository,
            InterviewLifecycleService interviewLifecycleService,
            @Value("${app.interview.timeout.idle-threshold:1800000}") long idleThresholdMillis
    ) {
        if (idleThresholdMillis <= 0) {
            throw new IllegalArgumentException("app.interview.timeout.idle-threshold must be positive");
        }
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewLifecycleService = interviewLifecycleService;
        this.idleThresholdMillis = idleThresholdMillis;
    }

    public AbortTimedOutResult abortTimedOutSessions(Pageable pageable, LocalDateTime now) {
        LocalDateTime referenceTime = now == null ? LocalDateTime.now() : now;
        LocalDateTime cutoff = referenceTime.minus(idleThresholdMillis, ChronoUnit.MILLIS);
        List<Long> candidateSessionIds = interviewSessionRepository.findTimedOutInProgressSessionIds(cutoff, pageable);

        int abortedCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        for (Long sessionId : candidateSessionIds) {
            try {
                boolean aborted = interviewLifecycleService.abortInterview(sessionId, cutoff, false);
                if (aborted) {
                    abortedCount++;
                } else {
                    skippedCount++;
                }
            } catch (BusinessException exception) {
                if (ErrorCode.INTERVIEW_004.equals(exception.getErrorCode())
                        || ErrorCode.INTERVIEW_007.equals(exception.getErrorCode())) {
                    skippedCount++;
                    log.info("event=interview_timeout_abort_skipped session_id={} code={}", sessionId, exception.getErrorCode());
                } else {
                    failureCount++;
                    log.warn("event=interview_timeout_abort_failed session_id={} code={} reason={}",
                            sessionId, exception.getErrorCode(), exception.getMessage());
                }
            } catch (Exception exception) {
                failureCount++;
                log.warn("event=interview_timeout_abort_failed session_id={} reason={}",
                        sessionId, exception.getMessage());
            }
        }

        return new AbortTimedOutResult(candidateSessionIds.size(), abortedCount, skippedCount, failureCount);
    }

    public record AbortTimedOutResult(
            int scannedCount,
            int abortedCount,
            int skippedCount,
            int failureCount
    ) {
    }
}
