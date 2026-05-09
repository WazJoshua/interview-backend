package com.josh.interviewj.resume.consumer;

import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeParseRetryService;
import com.josh.interviewj.resume.service.ResumeParseService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class ResumeParseConsumer {

    private static final String IDEMPOTENT_KEY_PREFIX = "resume:parse:processed:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ResumeRepository resumeRepository;
    private final ResumeParseOutboxRepository outboxRepository;
    private final ResumeParseService resumeParseService;
    private final ResumeParseRetryService resumeParseRetryService;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService virtualThreadExecutor;

    @Value("${app.mq.resume-parse.idempotent-cache-ttl:${app.redis-stream.resume-parse.idempotent-cache-ttl:86400000}}")
    private long idempotentCacheTtlMs;

    @Value("${app.mq.resume-parse.message-timeout:${app.redis-stream.resume-parse.message-timeout:600000}}")
    private long messageTimeoutMs;

    @Value("${app.mq.resume-parse.heartbeat-interval:${app.redis-stream.resume-parse.heartbeat-interval:30000}}")
    private long heartbeatIntervalMs;

    @Value("${app.mq.resume-parse.max-retries:${app.redis-stream.resume-parse.max-retries:3}}")
    private int maxRetries;

    @RabbitListener(queues = "${app.mq.resume-parse.queue}", containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(
            ResumeParseMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        if (message == null || message.resumeId() == null || message.resumeExternalId() == null || message.outboxId() == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Long outboxId = message.outboxId();
        if (isAlreadyProcessed(outboxId)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Long resumeId = message.resumeId();
        Resume resumeBeforeClaim = resumeRepository.findById(resumeId).orElse(null);
        if (resumeBeforeClaim == null) {
            markAsProcessed(outboxId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (resumeBeforeClaim.getStatus() == ResumeStatus.UPLOADED
                || resumeBeforeClaim.getStatus() == ResumeStatus.PARSED
                || resumeBeforeClaim.getStatus() == ResumeStatus.FAILED) {
            markAsProcessed(outboxId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(messageTimeoutMs));
        int claimed = resumeRepository.claimForProcessing(resumeId, deadline, ResumeStatus.PENDING, ResumeStatus.PARSING);
        if (claimed == 0) {
            if (outboxRepository.existsByRetrySourceOutboxId(outboxId)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            handleClaimRejected(resumeId, outboxId, channel, deliveryTag);
            return;
        }

        ScheduledFuture<?> heartbeatFuture = startHeartbeat(resumeId);
        try {
            resumeParseService.parse(resumeId);
            int updated = resumeRepository.markParsedWithTimestamp(
                    resumeId,
                    ResumeStatus.PARSING,
                    ResumeStatus.PARSED
            );
            if (updated == 1) {
                markAsProcessed(outboxId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            Resume currentResume = resumeRepository.findById(resumeId).orElse(null);
            if (currentResume != null && currentResume.getStatus() == ResumeStatus.PARSED) {
                markAsProcessed(outboxId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            channel.basicNack(deliveryTag, false, true);
        } catch (Exception exception) {
            handleFailure(message, channel, deliveryTag, exception);
        } finally {
            heartbeatFuture.cancel(false);
        }
    }

    private void handleFailure(
            ResumeParseMessage message,
            Channel channel,
            long deliveryTag,
            Exception exception
    ) throws IOException {
        Long resumeId = message.resumeId();
        Resume currentResume = resumeRepository.findById(resumeId).orElse(null);
        if (currentResume == null) {
            markAsProcessed(message.outboxId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        int currentRetryCount = currentResume.getRetryCount() == null ? 0 : currentResume.getRetryCount();
        if (currentRetryCount < maxRetries) {
            ResumeParseRetryService.RetrySchedulingResult result = resumeParseRetryService.scheduleRetry(
                    resumeId,
                    message.resumeExternalId(),
                    message.outboxId(),
                    safeMessage(exception)
            );
            if (result == ResumeParseRetryService.RetrySchedulingResult.SCHEDULED
                    || result == ResumeParseRetryService.RetrySchedulingResult.ALREADY_SCHEDULED) {
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        resumeRepository.markAsFailed(
                resumeId,
                safeMessage(exception),
                ResumeStatus.FAILED,
                ResumeStatus.PARSING
        );
        channel.basicNack(deliveryTag, false, false);
    }

    private void handleClaimRejected(Long resumeId, Long outboxId, Channel channel, long deliveryTag) throws IOException {
        Resume currentResume = resumeRepository.findById(resumeId).orElse(null);
        if (currentResume == null
                || currentResume.getStatus() == ResumeStatus.PARSED
                || currentResume.getStatus() == ResumeStatus.FAILED) {
            markAsProcessed(outboxId);
            channel.basicAck(deliveryTag, false);
            return;
        }
        channel.basicNack(deliveryTag, false, true);
    }

    private ScheduledFuture<?> startHeartbeat(Long resumeId) {
        AtomicBoolean running = new AtomicBoolean(false);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            virtualThreadExecutor.submit(() -> {
                try {
                    resumeRepository.heartbeat(resumeId, ResumeStatus.PARSING);
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

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
