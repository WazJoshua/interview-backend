package com.josh.interviewj.common.job;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxMonitorJob {

    private final ResumeParseOutboxRepository resumeParseOutboxRepository;
    private final KbDocumentOutboxRepository kbDocumentOutboxRepository;
    private final ResumeAnalysisOutboxRepository resumeAnalysisOutboxRepository;

    @Value("${app.outbox.backlog-alert-threshold:100}")
    private int backlogAlertThreshold;

    /**
     * Monitor outbox backlog and log a warning if it exceeds the threshold.
     */
    @Scheduled(fixedDelayString = "${app.outbox.monitor-interval:60000}")
    public void checkOutboxBacklog() {
        logBacklog("resume-parse", resumeParseOutboxRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)));
        logBacklog("kb-doc", kbDocumentOutboxRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)));
        logBacklog("resume-analysis", resumeAnalysisOutboxRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)));
    }

    private void logBacklog(String pipeline, long backlogCount) {
        if (backlogCount > backlogAlertThreshold) {
            log.warn("Outbox backlog alert: pipeline={}, pendingMessages={}, threshold={}",
                    pipeline, backlogCount, backlogAlertThreshold);
        }
    }
}
