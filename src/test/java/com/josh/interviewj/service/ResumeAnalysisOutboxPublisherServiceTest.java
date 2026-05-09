package com.josh.interviewj.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisOutboxPublisherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeAnalysisOutboxPublisherServiceTest {

    @Mock
    private ResumeAnalysisOutboxRepository outboxRepository;

    @Mock
    private AsyncTaskPublisher asyncTaskPublisher;

    @InjectMocks
    private ResumeAnalysisOutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        setPrivateInt(publisherService, "maxOutboxRetries", 5);
    }

    @Test
    void publishPendingOutboxMessages_NoPendingRecords_DoesNothing() {
        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of());

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository, never()).claimForProcessing(anyLong(), anyString(), any(), anyList());
        verify(asyncTaskPublisher, never()).publishResumeAnalysisTask(any());
    }

    @Test
    void publishPendingOutboxMessages_ClaimRejected_SkipsPublish() {
        ResumeAnalysisOutbox outbox = buildOutbox(OutboxStatus.NEW, 0);

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(0);

        publisherService.publishPendingOutboxMessages();

        verify(asyncTaskPublisher, never()).publishResumeAnalysisTask(any());
        verify(outboxRepository, never()).markAsSentWithOwner(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void publishPendingOutboxMessages_Success_PublishesAndMarksSent() {
        ResumeAnalysisOutbox outbox = buildOutbox(OutboxStatus.NEW, 0);

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeAnalysisTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.success());
        when(outboxRepository.markAsSentWithOwner(eq(1L), anyString(), eq(OutboxStatus.SENT), any(LocalDateTime.class), eq(OutboxStatus.PROCESSING)))
                .thenReturn(1);

        publisherService.publishPendingOutboxMessages();

        verify(asyncTaskPublisher).publishResumeAnalysisTask(any());
        verify(outboxRepository).markAsSentWithOwner(eq(1L), anyString(), eq(OutboxStatus.SENT), any(LocalDateTime.class), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_PublishThrows_PreparesRetry() {
        ResumeAnalysisOutbox outbox = buildOutbox(OutboxStatus.NEW, 0);

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeAnalysisTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("boom"));

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), eq(1), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), anyString(), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_ExhaustedRetries_MarksFailed() {
        ResumeAnalysisOutbox outbox = buildOutbox(OutboxStatus.RETRY, 4);

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeAnalysisTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("boom"));

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), eq("boom"), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), anyInt(), eq(OutboxStatus.PROCESSING));
    }

    /**
     * Builds a compact outbox fixture for publisher tests.
     *
     * @param status     outbox status
     * @param retryCount retry count
     * @return outbox fixture
     */
    private ResumeAnalysisOutbox buildOutbox(OutboxStatus status, int retryCount) {
        return ResumeAnalysisOutbox.builder()
                .id(1L)
                .reportId(11L)
                .resumeId(21L)
                .resumeExternalId(UUID.randomUUID())
                .status(status)
                .retryCount(retryCount)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Injects an int field value for configuration-style tests.
     *
     * @param target    target object
     * @param fieldName field name
     * @param value     value to set
     */
    private static void setPrivateInt(Object target, String fieldName, int value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set field: " + fieldName, e);
        }
    }
}
