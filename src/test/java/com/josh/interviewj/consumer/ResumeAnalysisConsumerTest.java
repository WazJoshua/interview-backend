package com.josh.interviewj.consumer;

import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.resume.consumer.ResumeAnalysisConsumer;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeAnalysisConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ResumeAnalysisOutboxRepository outboxRepository;
    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeAnalysisReportRepository reportRepository;
    @Mock
    private ResumeAnalysisService resumeAnalysisService;
    @Mock
    private ScheduledExecutorService heartbeatScheduler;
    @Mock
    private ExecutorService virtualThreadExecutor;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private Channel channel;

    @Test
    void onMessage_InvalidPayload_AcksAndSkips() throws Exception {
        ResumeAnalysisConsumer consumer = createConsumer();

        consumer.onMessage(new ResumeAnalysisMessage(null, null, null, null), channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(reportRepository, never()).claimForAnalysis(anyLong(), any(), any(), any());
    }

    @Test
    void onMessage_RetryableFailure_AcksAndCreatesNewOutbox() throws Exception {
        ResumeAnalysisConsumer consumer = createConsumer();
        ResumeAnalysisMessage message = new ResumeAnalysisMessage(11L, 21L, UUID.randomUUID(), 456L);

        when(stringRedisTemplate.hasKey("resume:analysis:processed:456")).thenReturn(false);
        when(reportRepository.findById(11L)).thenReturn(Optional.of(
                ResumeAnalysisReport.builder().id(11L).resumeId(21L).status(AnalysisStatus.PENDING).retryCount(0).updatedAt(LocalDateTime.now()).build()
        ));
        when(reportRepository.claimForAnalysis(eq(11L), any(LocalDateTime.class), eq(AnalysisStatus.PENDING), eq(AnalysisStatus.ANALYZING)))
                .thenReturn(1);
        when(resumeRepository.findById(21L)).thenReturn(Optional.of(
                Resume.builder().id(21L).externalId(message.resumeExternalId()).analysisStatus(AnalysisStatus.PENDING).build()
        ));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new ResumeAnalysisService.ResumeAnalysisExecutionException("retry-me", true))
                .when(resumeAnalysisService).performAnalysis(11L);

        consumer.onMessage(message, channel, 2L);

        verify(resumeAnalysisService).handleRetryableFailure(11L, "retry-me", 456L);
        verify(channel).basicAck(2L, false);
    }

    @Test
    void onMessage_TerminalFailure_NacksToDlqAndWritesIdempotentKey() throws Exception {
        ResumeAnalysisConsumer consumer = createConsumer();
        setPrivateLong(consumer, "idempotentCacheTtlMs", 7200000L);
        ResumeAnalysisMessage message = new ResumeAnalysisMessage(11L, 21L, UUID.randomUUID(), 456L);

        when(stringRedisTemplate.hasKey("resume:analysis:processed:456")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(any(), any(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(reportRepository.findById(11L)).thenReturn(Optional.of(
                ResumeAnalysisReport.builder().id(11L).resumeId(21L).status(AnalysisStatus.PENDING).retryCount(2).updatedAt(LocalDateTime.now()).build()
        ));
        when(reportRepository.claimForAnalysis(eq(11L), any(LocalDateTime.class), eq(AnalysisStatus.PENDING), eq(AnalysisStatus.ANALYZING)))
                .thenReturn(1);
        when(resumeRepository.findById(21L)).thenReturn(Optional.of(
                Resume.builder().id(21L).externalId(message.resumeExternalId()).analysisStatus(AnalysisStatus.PENDING).build()
        ));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        org.mockito.Mockito.doThrow(new ResumeAnalysisService.ResumeAnalysisExecutionException("terminal", false))
                .when(resumeAnalysisService).performAnalysis(11L);

        consumer.onMessage(message, channel, 3L);

        verify(resumeAnalysisService).handleTerminalFailure(11L, "terminal");
        verify(valueOperations).set("resume:analysis:processed:456", "1", 7200000L, TimeUnit.MILLISECONDS);
        verify(channel).basicNack(3L, false, false);
    }

    @Test
    void onMessage_ClaimRejectedWhileStillAnalyzing_RequeuesMessage() throws Exception {
        ResumeAnalysisConsumer consumer = createConsumer();
        ResumeAnalysisMessage message = new ResumeAnalysisMessage(11L, 21L, UUID.randomUUID(), 456L);

        when(stringRedisTemplate.hasKey("resume:analysis:processed:456")).thenReturn(false);
        when(reportRepository.findById(11L))
                .thenReturn(Optional.of(
                        ResumeAnalysisReport.builder().id(11L).resumeId(21L).status(AnalysisStatus.ANALYZING).retryCount(0).updatedAt(LocalDateTime.now()).build()
                ))
                .thenReturn(Optional.of(
                        ResumeAnalysisReport.builder().id(11L).resumeId(21L).status(AnalysisStatus.ANALYZING).retryCount(0).updatedAt(LocalDateTime.now()).build()
                ));
        when(reportRepository.claimForAnalysis(eq(11L), any(LocalDateTime.class), eq(AnalysisStatus.PENDING), eq(AnalysisStatus.ANALYZING)))
                .thenReturn(0);
        when(outboxRepository.existsByRetrySourceOutboxId(456L)).thenReturn(false);

        consumer.onMessage(message, channel, 4L);

        verify(channel).basicNack(4L, false, true);
        verify(channel, never()).basicAck(4L, false);
    }

    private ResumeAnalysisConsumer createConsumer() {
        ResumeAnalysisConsumer consumer = new ResumeAnalysisConsumer(
                stringRedisTemplate,
                outboxRepository,
                reportRepository,
                resumeRepository,
                resumeAnalysisService,
                heartbeatScheduler,
                virtualThreadExecutor
        );
        setPrivateLong(consumer, "idempotentCacheTtlMs", 86400000L);
        setPrivateLong(consumer, "messageTimeoutMs", 600000L);
        setPrivateLong(consumer, "heartbeatIntervalMs", 30000L);
        setPrivateInt(consumer, "maxRetries", 2);
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
