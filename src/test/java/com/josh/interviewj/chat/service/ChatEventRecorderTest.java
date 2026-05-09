package com.josh.interviewj.chat.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.repository.ChatEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatEventRecorderTest {

    @Mock
    private ChatEventRepository chatEventRepository;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordAfterCommit_RegistersEventAndPersistsAfterCommit() {
        ChatEventRecorder recorder = new ChatEventRecorder(chatEventRepository, JsonMapper.builder().build());
        TransactionSynchronizationManager.initSynchronization();

        recorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                10L,
                11L,
                ChatDomainType.RAG_QA,
                "ASSISTANT_MESSAGE_COMPLETED",
                "SUCCESS",
                Map.of("sourceCount", 2)
        ));

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        ArgumentCaptor<com.josh.interviewj.chat.model.ChatEvent> eventCaptor =
                ArgumentCaptor.forClass(com.josh.interviewj.chat.model.ChatEvent.class);
        verify(chatEventRepository).save(eventCaptor.capture());
        assertEquals(10L, eventCaptor.getValue().getChatSessionId());
        assertEquals(11L, eventCaptor.getValue().getChatMessageId());
        assertEquals("ASSISTANT_MESSAGE_COMPLETED", eventCaptor.getValue().getEventType());
        assertEquals("SUCCESS", eventCaptor.getValue().getStatus());
    }

    @Test
    void recordAfterCommit_WhenPersistFails_LogsWarnAndDoesNotThrow() {
        ChatEventRecorder recorder = new ChatEventRecorder(chatEventRepository, JsonMapper.builder().build());
        Logger logger = (Logger) LoggerFactory.getLogger(ChatEventRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new RuntimeException("boom")).when(chatEventRepository).save(any());

        try {
            recorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    20L,
                    null,
                    ChatDomainType.RAG_QA,
                    "RECENT_WINDOW_DEGRADED",
                    "DEGRADED",
                    Map.of("secret", "do-not-log", "chat_event_write_degraded", true)
            ));

            assertDoesNotThrow(() -> {
                for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCommit();
                }
            });

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("chat_event_write_degraded"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
