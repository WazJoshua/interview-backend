package com.josh.interviewj.consumer;

import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import com.josh.interviewj.resume.consumer.ResumeParseConsumer;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeParseRetryService;
import com.josh.interviewj.resume.service.ResumeParseService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeParseConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeParseOutboxRepository outboxRepository;

    @Mock
    private ResumeParseService resumeParseService;

    @Mock
    private ResumeParseRetryService retryService;

    @Mock
    private ScheduledExecutorService heartbeatScheduler;

    @Mock
    private ExecutorService virtualThreadExecutor;

    @Mock
    private Channel channel;

    @Test
    void onMessage_InvalidPayload_AcksAndSkips() throws Exception {
        ResumeParseConsumer consumer = newConsumer();

        consumer.onMessage(new ResumeParseMessage(null, null, null), channel, 9L);

        verify(channel).basicAck(9L, false);
        verify(resumeRepository, never()).claimForProcessing(anyLong(), any(), any(), any());
    }

    @Test
    void onMessage_RetryableFailure_SchedulesRetryAndAcks() throws Exception {
        ResumeParseConsumer consumer = newConsumer();
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);

        when(stringRedisTemplate.hasKey("resume:parse:processed:21")).thenReturn(false);
        when(resumeRepository.findById(11L))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PENDING).retryCount(0).build()))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(0).build()));
        when(resumeRepository.claimForProcessing(eq(11L), any(), eq(ResumeStatus.PENDING), eq(ResumeStatus.PARSING))).thenReturn(1);
        when(retryService.scheduleRetry(eq(11L), eq(message.resumeExternalId()), eq(21L), eq("boom")))
                .thenReturn(ResumeParseRetryService.RetrySchedulingResult.SCHEDULED);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(resumeParseService).parse(11L);

        consumer.onMessage(message, channel, 5L);

        verify(retryService).scheduleRetry(eq(11L), eq(message.resumeExternalId()), eq(21L), eq("boom"));
        verify(channel).basicAck(5L, false);
        verify(channel, never()).basicNack(5L, false, false);
    }

    @Test
    void onMessage_TerminalFailure_MarksFailedAndNacksToDlq() throws Exception {
        ResumeParseConsumer consumer = newConsumer();
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);

        when(stringRedisTemplate.hasKey("resume:parse:processed:21")).thenReturn(false);
        when(resumeRepository.findById(11L))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PENDING).retryCount(3).build()))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(3).build()));
        when(resumeRepository.claimForProcessing(eq(11L), any(), eq(ResumeStatus.PENDING), eq(ResumeStatus.PARSING))).thenReturn(1);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(resumeParseService).parse(11L);

        consumer.onMessage(message, channel, 7L);

        verify(retryService, never()).scheduleRetry(anyLong(), any(), anyLong(), any());
        verify(resumeRepository).markAsFailed(11L, "boom", ResumeStatus.FAILED, ResumeStatus.PARSING);
        verify(channel).basicNack(7L, false, false);
    }

    @Test
    void onMessage_ClaimRejectedWhileStillProcessing_RequeuesMessage() throws Exception {
        ResumeParseConsumer consumer = newConsumer();
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);

        when(stringRedisTemplate.hasKey("resume:parse:processed:21")).thenReturn(false);
        when(resumeRepository.findById(11L))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(1).build()))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(1).build()));
        when(resumeRepository.claimForProcessing(eq(11L), any(), eq(ResumeStatus.PENDING), eq(ResumeStatus.PARSING))).thenReturn(0);
        when(outboxRepository.existsByRetrySourceOutboxId(21L)).thenReturn(false);

        consumer.onMessage(message, channel, 8L);

        verify(channel).basicNack(8L, false, true);
        verify(channel, never()).basicAck(8L, false);
    }

    @Test
    void onMessage_StatusUpdateConflictAfterParse_RequeuesMessage() throws Exception {
        ResumeParseConsumer consumer = newConsumer();
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);

        when(stringRedisTemplate.hasKey("resume:parse:processed:21")).thenReturn(false);
        when(resumeRepository.findById(11L))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PENDING).retryCount(0).build()))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PARSING).retryCount(0).build()));
        when(resumeRepository.claimForProcessing(eq(11L), any(), eq(ResumeStatus.PENDING), eq(ResumeStatus.PARSING))).thenReturn(1);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        doNothing().when(resumeParseService).parse(11L);
        when(resumeRepository.markParsedWithTimestamp(eq(11L), eq(ResumeStatus.PARSING), eq(ResumeStatus.PARSED)))
                .thenReturn(0);

        consumer.onMessage(message, channel, 10L);

        verify(channel).basicNack(10L, false, true);
        verify(channel, never()).basicAck(10L, false);
    }

    @Test
    void onMessage_ParseSuccess_MarksParsedWithTimestampAndAcks() throws Exception {
        ResumeParseConsumer consumer = newConsumer();
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(stringRedisTemplate.hasKey("resume:parse:processed:21")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(resumeRepository.findById(11L))
                .thenReturn(Optional.of(Resume.builder().id(11L).status(ResumeStatus.PENDING).retryCount(0).build()));
        when(resumeRepository.claimForProcessing(eq(11L), any(), eq(ResumeStatus.PENDING), eq(ResumeStatus.PARSING))).thenReturn(1);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        doNothing().when(resumeParseService).parse(11L);
        when(resumeRepository.markParsedWithTimestamp(eq(11L), eq(ResumeStatus.PARSING), eq(ResumeStatus.PARSED)))
                .thenReturn(1);

        consumer.onMessage(message, channel, 11L);

        verify(resumeRepository).markParsedWithTimestamp(eq(11L), eq(ResumeStatus.PARSING), eq(ResumeStatus.PARSED));
        verify(channel).basicAck(11L, false);
        verify(channel, never()).basicNack(11L, false, true);
    }

    private ResumeParseConsumer newConsumer() {
        ResumeParseConsumer consumer = new ResumeParseConsumer(
                stringRedisTemplate,
                resumeRepository,
                outboxRepository,
                resumeParseService,
                retryService,
                heartbeatScheduler,
                virtualThreadExecutor
        );
        setPrivateLong(consumer, "idempotentCacheTtlMs", 86400000L);
        setPrivateLong(consumer, "messageTimeoutMs", 600000L);
        setPrivateLong(consumer, "heartbeatIntervalMs", 30000L);
        setPrivateInt(consumer, "maxRetries", 3);
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
