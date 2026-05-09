package com.josh.interviewj.interview.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewWebSocketEventPublisher extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByInterviewId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID interviewId = attributeAsUuid(session, "interviewId");
        if (interviewId == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
            }
            return;
        }
        sessionsByInterviewId.computeIfAbsent(interviewId, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID interviewId = attributeAsUuid(session, "interviewId");
        if (interviewId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByInterviewId.get(interviewId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByInterviewId.remove(interviewId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Interview websocket is read-only"));
    }

    public void publishAfterCommit(InterviewWebSocketPayloadFactory.InterviewEventPayload payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(payload);
                }
            });
            return;
        }
        publish(payload);
    }

    public void publish(InterviewWebSocketPayloadFactory.InterviewEventPayload payload) {
        Set<WebSocketSession> sessions = sessionsByInterviewId.get(payload.interviewId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json = serialize(payload);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException exception) {
                log.warn("event=interview_websocket_send_failed interview_id={} session_id={} reason={}",
                        payload.interviewId(), session.getId(), exception.getMessage());
            }
        }
    }

    private String serialize(InterviewWebSocketPayloadFactory.InterviewEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private UUID attributeAsUuid(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String stringValue) {
            try {
                return UUID.fromString(stringValue);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return null;
    }
}
