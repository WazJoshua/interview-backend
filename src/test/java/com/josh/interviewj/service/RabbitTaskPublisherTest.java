package com.josh.interviewj.service;

import com.josh.interviewj.common.mq.AsyncTaskPublisher;
import com.josh.interviewj.common.mq.RabbitTaskPublisher;
import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitTaskPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RabbitTaskPublisher publisher;

    private final MessageConverter messageConverter = new JacksonJsonMessageConverter();

    @BeforeEach
    void setUp() {
        publisher = new RabbitTaskPublisher(rabbitTemplate);
        setPrivateField(publisher, "resumeParseExchange", "resume.parse.exchange");
        setPrivateField(publisher, "resumeParseRoutingKey", "resume.parse");
        setPrivateField(publisher, "kbDocumentExchange", "kb.doc.exchange");
        setPrivateField(publisher, "kbDocumentRoutingKey", "kb.doc");
        setPrivateField(publisher, "resumeAnalysisExchange", "resume.analysis.exchange");
        setPrivateField(publisher, "resumeAnalysisRoutingKey", "resume.analysis");
        setPrivateField(publisher, "confirmTimeout", Duration.ofSeconds(2));
    }

    @Test
    void publishResumeParseTask_AckWithoutReturn_ReturnsSuccessAndSetsPersistentHeaders() throws Exception {
        ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlationDataCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq("resume.parse.exchange"),
                eq("resume.parse"),
                eq(message),
                postProcessorCaptor.capture(),
                correlationDataCaptor.capture()
        );

        AsyncTaskPublisher.PublishResult result = publisher.publishResumeParseTask(message);

        assertThat(result.published()).isTrue();
        Message amqpMessage = messageConverter.toMessage(message, new MessageProperties());
        Message processed = postProcessorCaptor.getValue().postProcessMessage(amqpMessage);
        String body = new String(processed.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"resumeId\":11");
        assertThat(body).contains("\"outboxId\":21");
        assertThat(processed.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(processed.getMessageProperties().getHeaders())
                .containsEntry("x-outbox-id", 21L)
                .containsEntry("x-entity-id", 11L)
                .containsEntry("x-message-type", "resume.parse")
                .containsEntry("x-origin-exchange", "resume.parse.exchange")
                .containsEntry("x-origin-routing-key", "resume.parse");
    }

    @Test
    void publishKbDocumentTask_Nack_ReturnsFailure() {
        KbDocumentMessage message = new KbDocumentMessage(7L, UUID.randomUUID(), 8L, UUID.randomUUID(), 9L);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("kb.doc.exchange"), eq("kb.doc"), eq(message), any(MessagePostProcessor.class), any(CorrelationData.class));

        AsyncTaskPublisher.PublishResult result = publisher.publishKbDocumentTask(message);

        assertThat(result.published()).isFalse();
        assertThat(result.failureReason()).contains("nack");
    }

    @Test
    void publishResumeAnalysisTask_ReturnedMessage_ReturnsFailure() {
        ResumeAnalysisMessage message = new ResumeAnalysisMessage(31L, 41L, UUID.randomUUID(), 51L);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            Message returnedBody = messageConverter.toMessage(message, new MessageProperties());
            correlationData.setReturned(new ReturnedMessage(returnedBody, 312, "NO_ROUTE", "resume.analysis.exchange", "resume.analysis"));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq("resume.analysis.exchange"), eq("resume.analysis"), eq(message), any(MessagePostProcessor.class), any(CorrelationData.class));

        AsyncTaskPublisher.PublishResult result = publisher.publishResumeAnalysisTask(message);

        assertThat(result.published()).isFalse();
        assertThat(result.failureReason()).contains("NO_ROUTE");
    }

    @Test
    void publishResumeParseTask_ConfirmTimeout_ReturnsFailure() {
        ResumeParseMessage message = new ResumeParseMessage(11L, UUID.randomUUID(), 21L);
        setPrivateField(publisher, "confirmTimeout", Duration.ofMillis(10));

        doAnswer(invocation -> null)
                .when(rabbitTemplate).convertAndSend(eq("resume.parse.exchange"), eq("resume.parse"), eq(message), any(MessagePostProcessor.class), any(CorrelationData.class));

        AsyncTaskPublisher.PublishResult result = publisher.publishResumeParseTask(message);

        assertThat(result.published()).isFalse();
        assertThat(result.failureReason()).contains("timeout");
    }

    private static void setPrivateField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set field: " + fieldName, e);
        }
    }
}
