package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KbDocumentRetryService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbDocumentRetryServiceTest {

    @Mock
    private KbDocumentRepository kbDocumentRepository;
    @Mock
    private KbDocumentOutboxRepository outboxRepository;
    @Mock
    private KnowledgeBaseReindexService knowledgeBaseReindexService;

    @InjectMocks
    private KbDocumentRetryService service;

    @BeforeEach
    void setUp() {
        setPrivateInt(service, "maxRetries", 3);
    }

    @Test
    void scheduleRetry_WhenAlreadyScheduled_ReturnsAlreadyScheduled() {
        when(outboxRepository.existsByRetrySourceOutboxId(22L)).thenReturn(true);

        var result = service.scheduleRetry(11L, 22L, "boom");

        assertThat(result).isEqualTo(KbDocumentRetryService.RetrySchedulingResult.ALREADY_SCHEDULED);
        verify(kbDocumentRepository, never()).markPendingForRetry(anyLong(), any(), any(), any());
    }

    @Test
    void scheduleRetry_WhenWithinBudget_CreatesFreshOutbox() {
        KbDocument document = KbDocument.builder().id(11L).kbId(9L).status(KbDocumentStatus.PROCESSING).build();
        when(outboxRepository.existsByRetrySourceOutboxId(22L)).thenReturn(false);
        when(kbDocumentRepository.markPendingForRetry(11L, "boom", KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)).thenReturn(1);
        when(kbDocumentRepository.findById(11L)).thenReturn(Optional.of(document));
        when(outboxRepository.countByDocumentId(11L)).thenReturn(2L);

        var result = service.scheduleRetry(11L, 22L, "boom");

        assertThat(result).isEqualTo(KbDocumentRetryService.RetrySchedulingResult.SCHEDULED);
        ArgumentCaptor<KbDocumentOutbox> captor = ArgumentCaptor.forClass(KbDocumentOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrySourceOutboxId()).isEqualTo(22L);
        assertThat(captor.getValue().getDocumentId()).isEqualTo(11L);
        assertThat(captor.getValue().getKbId()).isEqualTo(9L);
    }

    @Test
    void scheduleRetry_WhenBudgetExceeded_MarksFailed() {
        KbDocument document = KbDocument.builder().id(11L).kbId(9L).status(KbDocumentStatus.PROCESSING).build();
        when(outboxRepository.existsByRetrySourceOutboxId(22L)).thenReturn(false);
        when(kbDocumentRepository.markPendingForRetry(11L, "boom", KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)).thenReturn(1);
        when(kbDocumentRepository.findById(11L)).thenReturn(Optional.of(document));
        when(outboxRepository.countByDocumentId(11L)).thenReturn(5L);

        var result = service.scheduleRetry(11L, 22L, "boom");

        assertThat(result).isEqualTo(KbDocumentRetryService.RetrySchedulingResult.EXHAUSTED);
        verify(kbDocumentRepository).markFailed(11L, "boom", KbDocumentStatus.FAILED, KbDocumentStatus.PENDING);
        verify(knowledgeBaseReindexService).maybeCompleteReindex(9L);
        verify(outboxRepository, never()).save(any());
    }

    private static void setPrivateInt(Object target, String fieldName, int value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
