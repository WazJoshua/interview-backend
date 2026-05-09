package com.josh.interviewj.resume.consumer;

import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes resume analysis tasks from RabbitMQ with idempotency and retry control.
 */
@Component
@Slf4j
public class ResumeAnalysisConsumer {

    private static final String IDEMPOTENT_KEY_PREFIX = "resume:analysis:processed:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ResumeAnalysisOutboxRepository outboxRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisReportRepository reportRepository;
    private final ResumeAnalysisService resumeAnalysisService;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService virtualThreadExecutor;

    public ResumeAnalysisConsumer(
            StringRedisTemplate stringRedisTemplate,
            ResumeAnalysisOutboxRepository outboxRepository,
            ResumeAnalysisReportRepository reportRepository,
            ResumeRepository resumeRepository,
            ResumeAnalysisService resumeAnalysisService,
            ScheduledExecutorService heartbeatScheduler,
            ExecutorService virtualThreadExecutor
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.outboxRepository = outboxRepository;
        this.reportRepository = reportRepository;
        this.resumeRepository = resumeRepository;
        this.resumeAnalysisService = resumeAnalysisService;
        this.heartbeatScheduler = heartbeatScheduler;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Value("${app.mq.resume-analysis.idempotent-cache-ttl:${app.redis-stream.resume-analysis.idempotent-cache-ttl:86400000}}")
    private long idempotentCacheTtlMs;

    @Value("${app.mq.resume-analysis.message-timeout:${app.redis-stream.resume-analysis.message-timeout:600000}}")
    private long messageTimeoutMs;

    @Value("${app.mq.resume-analysis.heartbeat-interval:${app.redis-stream.resume-analysis.heartbeat-interval:30000}}")
    private long heartbeatIntervalMs;

    @Value("${app.resume-analysis.max-retries:2}")
    private int maxRetries;

    @RabbitListener(queues = "${app.mq.resume-analysis.queue}", containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(
            ResumeAnalysisMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        if (message == null || message.reportId() == null || message.resumeId() == null || message.resumeExternalId() == null || message.outboxId() == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (isAlreadyProcessed(message.outboxId())) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        ResumeAnalysisReport snapshot = reportRepository.findById(message.reportId()).orElse(null);
        if (snapshot == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(messageTimeoutMs));
        int claimed = reportRepository.claimForAnalysis(message.reportId(), deadline, AnalysisStatus.PENDING, AnalysisStatus.ANALYZING);
        if (claimed == 0) {
            if (outboxRepository.existsByRetrySourceOutboxId(message.outboxId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            handleClaimRejected(message.reportId(), channel, deliveryTag);
            return;
        }

        Resume resume = resumeRepository.findById(message.resumeId()).orElse(null);
        if (resume != null) {
            resume.setAnalysisStatus(AnalysisStatus.ANALYZING);
            resume.setErrorMessage(null);
            resumeRepository.save(resume);
        }

        ScheduledFuture<?> heartbeatFuture = startHeartbeat(message.reportId());
        try {
            resumeAnalysisService.performAnalysis(message.reportId());
            markAsProcessed(message.outboxId());
            channel.basicAck(deliveryTag, false);
        } catch (ResumeAnalysisService.ResumeAnalysisExecutionException exception) {
            handleFailure(snapshot, message, channel, deliveryTag, exception);
        } finally {
            heartbeatFuture.cancel(false);
        }
    }

    private void handleClaimRejected(Long reportId, Channel channel, long deliveryTag) throws IOException {
        ResumeAnalysisReport current = reportRepository.findById(reportId).orElse(null);
        if (current == null
                || current.getStatus() == AnalysisStatus.COMPLETED
                || current.getStatus() == AnalysisStatus.FAILED) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        channel.basicNack(deliveryTag, false, true);
    }

    private void handleFailure(
            ResumeAnalysisReport reportSnapshot,
            ResumeAnalysisMessage message,
            Channel channel,
            long deliveryTag,
            ResumeAnalysisService.ResumeAnalysisExecutionException exception
    ) throws IOException {
        String errorMessage = exception.getMessage();
        boolean exhausted = (reportSnapshot.getRetryCount() == null ? 0 : reportSnapshot.getRetryCount()) >= maxRetries;

        if (exception.isRetryable() && !exhausted) {
            resumeAnalysisService.handleRetryableFailure(reportSnapshot.getId(), errorMessage, message.outboxId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        resumeAnalysisService.handleTerminalFailure(reportSnapshot.getId(), errorMessage);
        markAsProcessed(message.outboxId());
        channel.basicNack(deliveryTag, false, false);
    }

    private ScheduledFuture<?> startHeartbeat(Long reportId) {
        AtomicBoolean running = new AtomicBoolean(false);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            virtualThreadExecutor.submit(() -> {
                try {
                    reportRepository.heartbeat(reportId, AnalysisStatus.ANALYZING);
                } finally {
                    running.set(false);
                }
            });
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean isAlreadyProcessed(Long outboxId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(IDEMPOTENT_KEY_PREFIX + outboxId));
    }

    private void markAsProcessed(Long outboxId) {
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        valueOperations.set(IDEMPOTENT_KEY_PREFIX + outboxId, "1", idempotentCacheTtlMs, TimeUnit.MILLISECONDS);
    }
}
