package com.josh.interviewj.interview.websocket;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Component
public class InterviewWebSocketPayloadFactory {

    public InterviewEventPayload create(
            UUID interviewId,
            UUID chatSessionId,
            String eventType,
            UUID messageId,
            Map<String, Object> payload
    ) {
        return new InterviewEventPayload(
                UUID.randomUUID(),
                interviewId,
                chatSessionId,
                eventType,
                LocalDateTime.now(),
                messageId,
                payload == null ? Map.of() : payload
        );
    }

    public record InterviewEventPayload(
            UUID eventId,
            UUID interviewId,
            UUID chatSessionId,
            String eventType,
            LocalDateTime occurredAt,
            UUID messageId,
            Map<String, Object> payload
    ) {
    }
}
