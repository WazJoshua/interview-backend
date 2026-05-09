package com.josh.interviewj.consumer;

import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.knowledgebase.consumer.KbDocumentConsumer;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KbDocumentIngestionService;
import com.josh.interviewj.knowledgebase.service.KbDocumentRetryService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import com.josh.interviewj.knowledgebase.consumer.IngestionFailureClassifier;
import com.josh.interviewj.knowledgebase.consumer.IngestionFailure;
import com.josh.interviewj.knowledgebase.consumer.IngestionFailureCategory;
import com.josh.interviewj.knowledgebase.consumer.IngestionStage;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbDocumentConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private KbDocumentRepository kbDocumentRepository;
    @Mock
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;
    @Mock
    private KbDocumentIngestionService kbDocumentIngestionService;
    @Mock
    private KnowledgeBaseReindexService knowledgeBaseReindexService;
    @Mock
    private IngestionFailureClassifier ingestionFailureClassifier;
    @Mock
    private KbDocumentRetryService kbDocumentRetryService;
    @Mock
    private ScheduledExecutorService heartbeatScheduler;
    @Mock
    private ExecutorService virtualThreadExecutor;
    @Mock
    private Channel channel;

    @Test
    void onMessage_InvalidPayload_AcksAndSkips() throws Exception {
        KbDocumentConsumer consumer = newConsumer();

        consumer.onMessage(new KbDocumentMessage(null, null, null, null, null), channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(kbDocumentRepository, never()).claimForProcessing(anyLong(), any(), any(), any());
    }

    @Test
    void onMessage_RetryableFailure_SchedulesRetryAndAcks() throws Exception {
        KbDocumentConsumer consumer = newConsumer();
        UUID kbExternalId = UUID.randomUUID();
        UUID docExternalId = UUID.randomUUID();
        KbDocumentMessage message = new KbDocumentMessage(10L, kbExternalId, 123L, docExternalId, 456L);

        when(stringRedisTemplate.hasKey("kb:doc:processed:456")).thenReturn(false);
        when(kbDocumentRepository.findById(123L))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PENDING).build()))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PROCESSING).build()));
        when(kbDocumentRepository.claimForProcessing(eq(123L), any(LocalDateTime.class), eq(KbDocumentStatus.PENDING), eq(KbDocumentStatus.PROCESSING)))
                .thenReturn(1);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new RuntimeException("retry-me")).when(kbDocumentIngestionService).ingestAndFinalize(123L, 456L, 7200000L);
        when(ingestionFailureClassifier.classify(any(Exception.class)))
                .thenReturn(new IngestionFailure(IngestionFailureCategory.INFRA_RETRYABLE, IngestionStage.UNKNOWN, "retry-me"));
        when(kbDocumentRetryService.scheduleRetry(123L, 456L, "retry-me"))
                .thenReturn(KbDocumentRetryService.RetrySchedulingResult.SCHEDULED);

        consumer.onMessage(message, channel, 2L);

        verify(kbDocumentRetryService).scheduleRetry(123L, 456L, "retry-me");
        verify(channel).basicAck(2L, false);
        verify(channel, never()).basicNack(2L, false, false);
    }

    @Test
    void onMessage_TerminalFailure_MarksFailedAndNacksToDlq() throws Exception {
        KbDocumentConsumer consumer = newConsumer();
        UUID kbExternalId = UUID.randomUUID();
        UUID docExternalId = UUID.randomUUID();
        KbDocumentMessage message = new KbDocumentMessage(10L, kbExternalId, 123L, docExternalId, 456L);

        when(stringRedisTemplate.hasKey("kb:doc:processed:456")).thenReturn(false);
        when(kbDocumentRepository.findById(123L))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PENDING).build()))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PROCESSING).build()));
        when(kbDocumentRepository.claimForProcessing(eq(123L), any(LocalDateTime.class), eq(KbDocumentStatus.PENDING), eq(KbDocumentStatus.PROCESSING)))
                .thenReturn(1);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new RuntimeException("terminal")).when(kbDocumentIngestionService).ingestAndFinalize(123L, 456L, 7200000L);
        when(ingestionFailureClassifier.classify(any(Exception.class)))
                .thenReturn(new IngestionFailure(IngestionFailureCategory.CONTENT_TERMINAL, IngestionStage.UNKNOWN, "terminal"));

        consumer.onMessage(message, channel, 3L);

        verify(kbDocumentRepository).markFailed(123L, "terminal", KbDocumentStatus.FAILED, KbDocumentStatus.PROCESSING);
        verify(knowledgeBaseReindexService).maybeCompleteReindex(10L);
        verify(channel).basicNack(3L, false, false);
    }

    @Test
    void onMessage_ClaimRejectedWhileStillProcessing_RequeuesMessage() throws Exception {
        KbDocumentConsumer consumer = newConsumer();
        KbDocumentMessage message = new KbDocumentMessage(10L, UUID.randomUUID(), 123L, UUID.randomUUID(), 456L);

        when(stringRedisTemplate.hasKey("kb:doc:processed:456")).thenReturn(false);
        when(kbDocumentRepository.findById(123L))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PROCESSING).build()))
                .thenReturn(Optional.of(KbDocument.builder().id(123L).kbId(10L).status(KbDocumentStatus.PROCESSING).build()));
        when(kbDocumentRepository.claimForProcessing(eq(123L), any(LocalDateTime.class), eq(KbDocumentStatus.PENDING), eq(KbDocumentStatus.PROCESSING)))
                .thenReturn(0);
        when(kbDocumentOutboxRepository.existsByRetrySourceOutboxId(456L)).thenReturn(false);

        consumer.onMessage(message, channel, 4L);

        verify(channel).basicNack(4L, false, true);
        verify(channel, never()).basicAck(4L, false);
    }

    private KbDocumentConsumer newConsumer() {
        KbDocumentConsumer consumer = new KbDocumentConsumer(
                stringRedisTemplate,
                kbDocumentRepository,
                kbDocumentOutboxRepository,
                kbDocumentIngestionService,
                knowledgeBaseReindexService,
                ingestionFailureClassifier,
                kbDocumentRetryService,
                heartbeatScheduler,
                virtualThreadExecutor
        );
        setPrivateLong(consumer, "idempotentCacheTtlMs", 7200000L);
        setPrivateLong(consumer, "messageTimeoutMs", 600000L);
        setPrivateLong(consumer, "heartbeatIntervalMs", 30000L);
        return consumer;
    }

    private static void setPrivateLong(Object target, String fieldName, long value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setLong(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set field: " + fieldName, e);
        }
    }
}
