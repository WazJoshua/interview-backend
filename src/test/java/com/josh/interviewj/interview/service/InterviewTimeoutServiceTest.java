package com.josh.interviewj.interview.service;

import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewTimeoutServiceTest {

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewLifecycleService interviewLifecycleService;

    private InterviewTimeoutService interviewTimeoutService;

    @BeforeEach
    void setUp() {
        interviewTimeoutService = new InterviewTimeoutService(
                interviewSessionRepository,
                interviewLifecycleService,
                30 * 60 * 1000L
        );
    }

    @Test
    void abortTimedOutSessions_UsesCutoffAndCountsAbortedSkippedAndFailed() {
        LocalDateTime now = LocalDateTime.parse("2026-03-28T10:00:00");
        LocalDateTime cutoff = LocalDateTime.parse("2026-03-28T09:30:00");
        PageRequest pageRequest = PageRequest.of(0, 50);
        when(interviewSessionRepository.findTimedOutInProgressSessionIds(cutoff, pageRequest))
                .thenReturn(List.of(101L, 102L, 103L));
        when(interviewLifecycleService.abortInterview(101L, cutoff, false)).thenReturn(true);
        when(interviewLifecycleService.abortInterview(102L, cutoff, false)).thenReturn(false);
        when(interviewLifecycleService.abortInterview(103L, cutoff, false)).thenThrow(new IllegalStateException("boom"));

        InterviewTimeoutService.AbortTimedOutResult result =
                interviewTimeoutService.abortTimedOutSessions(pageRequest, now);

        assertEquals(3, result.scannedCount());
        assertEquals(1, result.abortedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(1, result.failureCount());
    }

    @Test
    void abortTimedOutSessions_ProcessesEachCandidateIndependently() {
        LocalDateTime now = LocalDateTime.parse("2026-03-28T10:00:00");
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(interviewSessionRepository.findTimedOutInProgressSessionIds(
                LocalDateTime.parse("2026-03-28T09:30:00"),
                pageRequest
        )).thenReturn(List.of(201L, 202L));
        when(interviewLifecycleService.abortInterview(201L, LocalDateTime.parse("2026-03-28T09:30:00"), false))
                .thenThrow(new RuntimeException("first failed"));
        when(interviewLifecycleService.abortInterview(202L, LocalDateTime.parse("2026-03-28T09:30:00"), false))
                .thenReturn(true);

        InterviewTimeoutService.AbortTimedOutResult result =
                interviewTimeoutService.abortTimedOutSessions(pageRequest, now);

        assertEquals(2, result.scannedCount());
        assertEquals(1, result.abortedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(1, result.failureCount());
        verify(interviewLifecycleService).abortInterview(201L, LocalDateTime.parse("2026-03-28T09:30:00"), false);
        verify(interviewLifecycleService).abortInterview(202L, LocalDateTime.parse("2026-03-28T09:30:00"), false);
    }

    @Test
    void constructor_WhenIdleThresholdIsNonPositive_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new InterviewTimeoutService(
                interviewSessionRepository,
                interviewLifecycleService,
                0
        ));
    }
}
