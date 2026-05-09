package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatEvent;
import com.josh.interviewj.chat.repository.ChatEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatEventRecorder {

    private final ChatEventRepository chatEventRepository;
    private final ObjectMapper objectMapper;

    public void recordAfterCommit(ChatEventDraft draft) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    persistSafely(draft);
                }
            });
            return;
        }
        persistSafely(draft);
    }

    private void persistSafely(ChatEventDraft draft) {
        try {
            chatEventRepository.save(ChatEvent.builder()
                    .chatSessionId(draft.chatSessionId())
                    .chatMessageId(draft.chatMessageId())
                    .domainType(draft.domainType())
                    .eventType(draft.eventType())
                    .status(draft.status())
                    .payload(serializePayload(sanitizePayload(draft.payload())))
                    .build());
        } catch (Exception exception) {
            log.warn(
                    "event=chat_event_write_degraded chat_event_write_degraded=true chat_session_id={} chat_message_id={} event_type={} reason={}",
                    draft.chatSessionId(),
                    draft.chatMessageId(),
                    draft.eventType(),
                    exception.getMessage()
            );
        }
    }

    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (payload == null) {
            return sanitized;
        }
        payload.forEach((key, value) -> {
            String loweredKey = key.toLowerCase(Locale.ROOT);
            if (loweredKey.contains("secret")
                    || loweredKey.contains("password")
                    || loweredKey.contains("token")
                    || loweredKey.contains("authorization")
                    || loweredKey.contains("api_key")) {
                return;
            }
            sanitized.put(key, value);
        });
        return sanitized;
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    public record ChatEventDraft(
            Long chatSessionId,
            Long chatMessageId,
            ChatDomainType domainType,
            String eventType,
            String status,
            Map<String, Object> payload
    ) {
    }
}
