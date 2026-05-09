package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KbDocumentOutboxPublisherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@SpringBootTest
class KbDocumentPublisherIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KbDocumentRepository kbDocumentRepository;

    @Autowired
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Autowired
    private KbDocumentOutboxPublisherService publisherService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        kbDocumentOutboxRepository.deleteAllInBatch();
        kbDocumentRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void publishPendingOutboxMessages_Success_MarksOutboxSent() {
        KbDocumentOutbox outbox = createFixture(OutboxStatus.NEW, 0);
        ReflectionTestUtils.setField(publisherService, "maxOutboxRetries", 5);
        stubPublishSuccess();

        publisherService.publishPendingOutboxMessages();

        KbDocumentOutbox updated = kbDocumentOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, updated.getStatus());
        assertNotNull(updated.getOwner());
        assertNotNull(updated.getSentAt());
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void publishPendingOutboxMessages_PublishFailure_MarksOutboxRetry() {
        KbDocumentOutbox outbox = createFixture(OutboxStatus.NEW, 0);
        ReflectionTestUtils.setField(publisherService, "maxOutboxRetries", 5);
        stubPublishFailure("redis-down");

        publisherService.publishPendingOutboxMessages();

        KbDocumentOutbox updated = kbDocumentOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.RETRY, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNull(updated.getOwner());
        assertNull(updated.getSentAt());
    }

    @Test
    void publishPendingOutboxMessages_RetryBudgetExceeded_MarksOutboxFailed() {
        KbDocumentOutbox outbox = createFixture(OutboxStatus.RETRY, 1);
        ReflectionTestUtils.setField(publisherService, "maxOutboxRetries", 2);
        stubPublishFailure("still-down");

        publisherService.publishPendingOutboxMessages();

        KbDocumentOutbox updated = kbDocumentOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.FAILED, updated.getStatus());
        assertNotNull(updated.getOwner());
        assertEquals("still-down", updated.getErrorMessage());
        assertNull(updated.getSentAt());
    }

    private KbDocumentOutbox createFixture(OutboxStatus outboxStatus, int retryCount) {
        User owner = userRepository.save(User.builder()
                .username("publisher-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());

        KnowledgeBase knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Publisher KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("publisher.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://publisher.pdf")
                .status(KbDocumentStatus.PENDING)
                .build());

        return kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                .kbId(knowledgeBase.getId())
                .documentId(document.getId())
                .status(outboxStatus)
                .retryCount(retryCount)
                .build());
    }

    private void stubPublishSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private void stubPublishFailure(String reason) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
