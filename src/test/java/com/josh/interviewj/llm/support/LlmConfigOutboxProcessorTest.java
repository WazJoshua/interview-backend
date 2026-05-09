package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmRuntimeProperties;
import com.josh.interviewj.usage.model.LlmConfigChangeOutbox;
import com.josh.interviewj.usage.repository.LlmConfigChangeOutboxRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmConfigOutboxProcessorTest {

    @Test
    void publishPendingChanges_PublishesPendingEventAndMarksPublished() {
        LlmConfigChangeOutboxRepository outboxRepository = mock(LlmConfigChangeOutboxRepository.class);
        LlmConfigInvalidationPublisher publisher = mock(LlmConfigInvalidationPublisher.class);
        LlmRuntimeProperties properties = runtimeProperties(3);
        LlmConfigChangeOutbox pending = pendingOutbox(1L, 9L, 0);

        when(outboxRepository.findTop100ByPublishStatusOrderByCreatedAtAscIdAsc("PENDING"))
                .thenReturn(List.of(pending));

        LlmConfigOutboxProcessor processor = new LlmConfigOutboxProcessor(outboxRepository, publisher, properties);

        processor.publishPendingChanges();

        verify(publisher).publishInvalidation(9L, "ROUTING_UPDATED", "{\"purpose\":\"analysis\"}");
        verify(outboxRepository).save(argThat(saved ->
                saved.getId().equals(1L)
                        && "PUBLISHED".equals(saved.getPublishStatus())
                        && saved.getPublishAttempts() == 1
                        && saved.getPublishedAt() != null
                        && saved.getLastError() == null
        ));
    }

    @Test
    void publishPendingChanges_WhenPublishFailsBeforeMaxAttempts_KeepsRecordPendingForRetry() {
        LlmConfigChangeOutboxRepository outboxRepository = mock(LlmConfigChangeOutboxRepository.class);
        LlmConfigInvalidationPublisher publisher = mock(LlmConfigInvalidationPublisher.class);
        LlmRuntimeProperties properties = runtimeProperties(3);
        LlmConfigChangeOutbox pending = pendingOutbox(2L, 10L, 0);

        when(outboxRepository.findTop100ByPublishStatusOrderByCreatedAtAscIdAsc("PENDING"))
                .thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(publisher)
                .publishInvalidation(eq(10L), eq("ROUTING_UPDATED"), eq("{\"purpose\":\"analysis\"}"));

        LlmConfigOutboxProcessor processor = new LlmConfigOutboxProcessor(outboxRepository, publisher, properties);

        processor.publishPendingChanges();

        verify(outboxRepository).save(argThat(saved ->
                saved.getId().equals(2L)
                        && "PENDING".equals(saved.getPublishStatus())
                        && saved.getPublishAttempts() == 1
                        && saved.getPublishedAt() == null
                        && "redis down".equals(saved.getLastError())
        ));
    }

    @Test
    void publishPendingChanges_WhenPublishFailsAtMaxAttempts_MarksRecordFailed() {
        LlmConfigChangeOutboxRepository outboxRepository = mock(LlmConfigChangeOutboxRepository.class);
        LlmConfigInvalidationPublisher publisher = mock(LlmConfigInvalidationPublisher.class);
        LlmRuntimeProperties properties = runtimeProperties(3);
        LlmConfigChangeOutbox pending = pendingOutbox(3L, 11L, 2);

        when(outboxRepository.findTop100ByPublishStatusOrderByCreatedAtAscIdAsc("PENDING"))
                .thenReturn(List.of(pending));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(publisher)
                .publishInvalidation(eq(11L), eq("ROUTING_UPDATED"), eq("{\"purpose\":\"analysis\"}"));

        LlmConfigOutboxProcessor processor = new LlmConfigOutboxProcessor(outboxRepository, publisher, properties);

        processor.publishPendingChanges();

        verify(outboxRepository).save(argThat(saved ->
                saved.getId().equals(3L)
                        && "FAILED".equals(saved.getPublishStatus())
                        && saved.getPublishAttempts() == 3
                        && saved.getPublishedAt() == null
                        && "redis down".equals(saved.getLastError())
        ));
    }

    @Test
    void publishPendingChanges_WhenNoPendingRecord_DoesNothing() {
        LlmConfigChangeOutboxRepository outboxRepository = mock(LlmConfigChangeOutboxRepository.class);
        LlmConfigInvalidationPublisher publisher = mock(LlmConfigInvalidationPublisher.class);
        LlmRuntimeProperties properties = runtimeProperties(3);

        when(outboxRepository.findTop100ByPublishStatusOrderByCreatedAtAscIdAsc("PENDING"))
                .thenReturn(List.of());

        LlmConfigOutboxProcessor processor = new LlmConfigOutboxProcessor(outboxRepository, publisher, properties);

        processor.publishPendingChanges();

        verify(publisher, never()).publishInvalidation(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any(LlmConfigChangeOutbox.class));
    }

    private LlmRuntimeProperties runtimeProperties(int maxAttempts) {
        LlmRuntimeProperties properties = new LlmRuntimeProperties();
        properties.getOutbox().setMaxPublishAttempts(maxAttempts);
        properties.getOutbox().setEnabled(true);
        return properties;
    }

    private LlmConfigChangeOutbox pendingOutbox(Long id, Long version, int attempts) {
        return LlmConfigChangeOutbox.builder()
                .id(id)
                .configVersion(version)
                .changeType("ROUTING_UPDATED")
                .payload("{\"purpose\":\"analysis\"}")
                .publishStatus("PENDING")
                .publishAttempts(attempts)
                .build();
    }
}
