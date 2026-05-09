package com.josh.interviewj.common.mq;

import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class RabbitTaskPublisher implements AsyncTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.mq.resume-parse.exchange}")
    private String resumeParseExchange;

    @Value("${app.mq.resume-parse.routing-key}")
    private String resumeParseRoutingKey;

    @Value("${app.mq.kb-doc.exchange}")
    private String kbDocumentExchange;

    @Value("${app.mq.kb-doc.routing-key}")
    private String kbDocumentRoutingKey;

    @Value("${app.mq.resume-analysis.exchange}")
    private String resumeAnalysisExchange;

    @Value("${app.mq.resume-analysis.routing-key}")
    private String resumeAnalysisRoutingKey;

    @Value("${app.mq.publisher-confirm-timeout:PT5S}")
    private Duration confirmTimeout = Duration.ofSeconds(5);

    @Override
    public PublishResult publishResumeParseTask(ResumeParseMessage message) {
        return publish(
                resumeParseExchange,
                resumeParseRoutingKey,
                message,
                "resume.parse",
                message.outboxId(),
                message.resumeId()
        );
    }

    @Override
    public PublishResult publishKbDocumentTask(KbDocumentMessage message) {
        return publish(
                kbDocumentExchange,
                kbDocumentRoutingKey,
                message,
                "kb.doc",
                message.outboxId(),
                message.documentId()
        );
    }

    @Override
    public PublishResult publishResumeAnalysisTask(ResumeAnalysisMessage message) {
        return publish(
                resumeAnalysisExchange,
                resumeAnalysisRoutingKey,
                message,
                "resume.analysis",
                message.outboxId(),
                message.reportId()
        );
    }

    private PublishResult publish(
            String exchange,
            String routingKey,
            Object payload,
            String messageType,
            Long outboxId,
            Long entityId
    ) {
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> enrichMessage(message, outboxId, entityId, messageType, exchange, routingKey), correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(confirmTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                return PublishResult.failure("message returned: " + returned.getReplyText());
            }
            if (confirm == null || !confirm.ack()) {
                String reason = confirm == null ? "publisher confirm missing" : confirm.reason();
                return PublishResult.failure(reason == null || reason.isBlank() ? "publisher nack" : reason);
            }
            return PublishResult.success();
        } catch (TimeoutException e) {
            return PublishResult.failure("publisher confirm timeout");
        } catch (AmqpException e) {
            return PublishResult.failure(safeMessage(e));
        } catch (Exception e) {
            return PublishResult.failure(safeMessage(e));
        }
    }

    private Message enrichMessage(
            Message message,
            Long outboxId,
            Long entityId,
            String messageType,
            String exchange,
            String routingKey
    ) {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        message.getMessageProperties().setType(messageType);
        message.getMessageProperties().setHeader("x-outbox-id", outboxId);
        message.getMessageProperties().setHeader("x-entity-id", entityId);
        message.getMessageProperties().setHeader("x-message-type", messageType);
        message.getMessageProperties().setHeader("x-origin-exchange", exchange);
        message.getMessageProperties().setHeader("x-origin-routing-key", routingKey);
        return message;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
