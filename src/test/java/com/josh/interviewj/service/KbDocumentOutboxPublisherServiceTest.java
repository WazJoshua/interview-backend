package com.josh.interviewj.service;

import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.service.KbDocumentOutboxPublisherService;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbDocumentOutboxPublisherServiceTest {

    @Mock
    private KbDocumentOutboxRepository outboxRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private AsyncTaskPublisher asyncTaskPublisher;

    @InjectMocks
    private KbDocumentOutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        setPrivateInt(publisherService, "maxOutboxRetries", 5);
    }

    @Test
    void publishPendingOutboxMessages_NoPendingRecords_DoesNothing() {
        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of());

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository, never()).claimForProcessing(any(), anyString(), any(), anyList());
        verify(asyncTaskPublisher, never()).publishKbDocumentTask(any());
    }

    @Test
    void publishPendingOutboxMessages_ClaimRejected_SkipsPublish() {
        KbDocumentOutbox outbox = KbDocumentOutbox.builder()
                .id(1L)
                .kbId(11L)
                .documentId(21L)
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList())).thenReturn(0);

        publisherService.publishPendingOutboxMessages();

        verify(asyncTaskPublisher, never()).publishKbDocumentTask(any());
        verify(outboxRepository, never()).markAsSentWithOwner(any(), anyString(), any(), any(), any());
    }

    @Test
    void publishPendingOutboxMessages_MissingDocumentOrKnowledgeBase_MarksFailed() {
        KbDocumentOutbox outbox = KbDocumentOutbox.builder()
                .id(1L)
                .kbId(11L)
                .documentId(21L)
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList())).thenReturn(1);
        when(kbDocumentRepository.findById(21L)).thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findById(11L)).thenReturn(Optional.empty());

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), eq("KB document or knowledge base not found"), eq(OutboxStatus.PROCESSING));
        verify(asyncTaskPublisher, never()).publishKbDocumentTask(any());
    }

    @Test
    void publishPendingOutboxMessages_Success_PublishesAndMarksSent() {
        UUID kbExternalId = UUID.randomUUID();
        UUID documentExternalId = UUID.randomUUID();

        KbDocumentOutbox outbox = KbDocumentOutbox.builder()
                .id(1L)
                .kbId(11L)
                .documentId(21L)
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        KbDocument document = KbDocument.builder()
                .id(21L)
                .externalId(documentExternalId)
                .kbId(11L)
                .status(KbDocumentStatus.PENDING)
                .build();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(11L)
                .externalId(kbExternalId)
                .userId(2L)
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList())).thenReturn(1);
        when(kbDocumentRepository.findById(21L)).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findById(11L)).thenReturn(Optional.of(knowledgeBase));
        when(asyncTaskPublisher.publishKbDocumentTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.success());
        when(outboxRepository.markAsSentWithOwner(eq(1L), anyString(), eq(OutboxStatus.SENT), any(LocalDateTime.class), eq(OutboxStatus.PROCESSING)))
                .thenReturn(1);

        publisherService.publishPendingOutboxMessages();

        verify(asyncTaskPublisher).publishKbDocumentTask(any());
        verify(outboxRepository).markAsSentWithOwner(eq(1L), anyString(), eq(OutboxStatus.SENT), any(LocalDateTime.class), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_PublishThrows_PreparesRetry() {
        KbDocumentOutbox outbox = KbDocumentOutbox.builder()
                .id(1L)
                .kbId(11L)
                .documentId(21L)
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        KbDocument document = KbDocument.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .kbId(11L)
                .status(KbDocumentStatus.PENDING)
                .build();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .userId(2L)
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList())).thenReturn(1);
        when(kbDocumentRepository.findById(21L)).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findById(11L)).thenReturn(Optional.of(knowledgeBase));
        when(asyncTaskPublisher.publishKbDocumentTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("boom"));

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), eq(1), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), anyString(), eq(OutboxStatus.PROCESSING));
    }

    @Test
    void publishPendingOutboxMessages_ExhaustedRetries_MarksFailed() {
        KbDocumentOutbox outbox = KbDocumentOutbox.builder()
                .id(1L)
                .kbId(11L)
                .documentId(21L)
                .status(OutboxStatus.RETRY)
                .retryCount(4)
                .createdAt(LocalDateTime.now())
                .build();
        KbDocument document = KbDocument.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .kbId(11L)
                .status(KbDocumentStatus.PENDING)
                .build();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .userId(2L)
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();

        when(outboxRepository.findByStatusInOrderByCreatedAtAsc(List.of(OutboxStatus.NEW, OutboxStatus.RETRY)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.claimForProcessing(eq(1L), anyString(), eq(OutboxStatus.PROCESSING), anyList())).thenReturn(1);
        when(kbDocumentRepository.findById(21L)).thenReturn(Optional.of(document));
        when(knowledgeBaseRepository.findById(11L)).thenReturn(Optional.of(knowledgeBase));
        when(asyncTaskPublisher.publishKbDocumentTask(any()))
                .thenReturn(AsyncTaskPublisher.PublishResult.failure("boom"));

        publisherService.publishPendingOutboxMessages();

        verify(outboxRepository).markAsFailedWithOwner(eq(1L), anyString(), eq(OutboxStatus.FAILED), eq("boom"), eq(OutboxStatus.PROCESSING));
        verify(outboxRepository, never()).prepareRetryWithOwner(eq(1L), anyString(), eq(OutboxStatus.RETRY), anyInt(), eq(OutboxStatus.PROCESSING));
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
