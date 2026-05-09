package com.josh.interviewj.service;

import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeParseRetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeParseRetryServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeParseOutboxRepository outboxRepository;

    @InjectMocks
    private ResumeParseRetryService service;

    @Test
    void scheduleRetry_WhenSourceAlreadyScheduled_ReturnsAlreadyScheduled() {
        when(outboxRepository.existsByRetrySourceOutboxId(22L)).thenReturn(true);

        ResumeParseRetryService.RetrySchedulingResult result =
                service.scheduleRetry(11L, UUID.randomUUID(), 22L, "boom");

        assertThat(result).isEqualTo(ResumeParseRetryService.RetrySchedulingResult.ALREADY_SCHEDULED);
        verify(resumeRepository, never()).findById(anyLong());
        verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any(ResumeParseOutbox.class));
    }

    @Test
    void scheduleRetry_WhenRetryNeeded_UpdatesResumeAndCreatesFreshOutbox() {
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(1).build();
        when(outboxRepository.existsByRetrySourceOutboxId(22L)).thenReturn(false);
        when(resumeRepository.findById(11L)).thenReturn(Optional.of(resume));

        ResumeParseRetryService.RetrySchedulingResult result =
                service.scheduleRetry(11L, resumeExternalId, 22L, "boom");

        assertThat(result).isEqualTo(ResumeParseRetryService.RetrySchedulingResult.SCHEDULED);
        assertThat(resume.getStatus()).isEqualTo(ResumeStatus.PENDING);
        assertThat(resume.getRetryCount()).isEqualTo(2);
        assertThat(resume.getErrorMessage()).isEqualTo("boom");
        verify(resumeRepository).save(resume);

        ArgumentCaptor<ResumeParseOutbox> captor = ArgumentCaptor.forClass(ResumeParseOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrySourceOutboxId()).isEqualTo(22L);
        assertThat(captor.getValue().getResumeId()).isEqualTo(11L);
        assertThat(captor.getValue().getResumeExternalId()).isEqualTo(resumeExternalId);
    }
}
