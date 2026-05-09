package com.josh.interviewj.resume.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResumeParseRetryService {

    private final ResumeRepository resumeRepository;
    private final ResumeParseOutboxRepository outboxRepository;

    @Transactional
    public RetrySchedulingResult scheduleRetry(Long resumeId, UUID resumeExternalId, Long sourceOutboxId, String errorMessage) {
        if (outboxRepository.existsByRetrySourceOutboxId(sourceOutboxId)) {
            return RetrySchedulingResult.ALREADY_SCHEDULED;
        }

        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            return RetrySchedulingResult.SKIPPED;
        }

        resume.setStatus(ResumeStatus.PENDING);
        resume.setRetryCount((resume.getRetryCount() == null ? 0 : resume.getRetryCount()) + 1);
        resume.setErrorMessage(errorMessage);
        resumeRepository.save(resume);

        outboxRepository.save(ResumeParseOutbox.builder()
                .resumeId(resumeId)
                .resumeExternalId(resumeExternalId)
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .retrySourceOutboxId(sourceOutboxId)
                .errorMessage(errorMessage)
                .build());
        return RetrySchedulingResult.SCHEDULED;
    }

    public enum RetrySchedulingResult {
        SCHEDULED,
        ALREADY_SCHEDULED,
        SKIPPED
    }
}
