package com.josh.interviewj.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.common.outbox.OutboxPublisherService;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private ResumeParseOutboxRepository outboxRepository;

    @Mock
    private AsyncTaskPublisher asyncTaskPublisher;

    @Mock
    private ResumeRepository resumeRepository;

    @InjectMocks
    private OutboxPublisherService service;

    @BeforeEach
    void setUp() {
        setPrivateInt(service, "maxOutboxRetries", 5);
    }

    @Test
    void publishPendingOutboxMessages_PublishSuccess_MarksSent() {
        ResumeParseOutbox outbox = buildOutbox(OutboxStatus.NEW, 0);
        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeParseTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.success());

        service.publishPendingOutboxMessages();

        verify(outboxRepository).markAsSentWithOwner(eq(1L), anyString(), eq(OutboxStatus.SENT), any(LocalDateTime.class), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), eq(1), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_PublishFailure_PreparesRetry() {
        ResumeParseOutbox outbox = buildOutbox(OutboxStatus.NEW, 0);
        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeParseTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("nack"));

        service.publishPendingOutboxMessages();

        verify(outboxRepository).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), eq(1), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), anyString(), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_ExhaustedRetries_MarksOutboxAndResumeFailed() {
        ResumeParseOutbox outbox = buildOutbox(OutboxStatus.RETRY, 4);
        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList()))
                .thenReturn(1);
        when(asyncTaskPublisher.publishResumeParseTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("timeout"));
        when(outboxRepository.markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), eq("timeout"), eq(OutboxStatus.PROCESSING)))
                .thenReturn(1);

        service.publishPendingOutboxMessages();

        verify(outboxRepository).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), eq("timeout"), eq(OutboxStatus.PROCESSING));
        verify(resumeRepository).markAsFailedFromOutbox(
                eq(11L),
                eq("Outbox publish failed: timeout"),
                eq(ResumeStatus.FAILED),
                eq(List.of(ResumeStatus.PENDING, ResumeStatus.PARSING))
        );
    }

    private ResumeParseOutbox buildOutbox(OutboxStatus status, int retryCount) {
        return ResumeParseOutbox.builder()
                .id(1L)
                .resumeId(11L)
                .resumeExternalId(UUID.randomUUID())
                .status(status)
                .retryCount(retryCount)
                .createdAt(LocalDateTime.now())
                .build();
    }

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
