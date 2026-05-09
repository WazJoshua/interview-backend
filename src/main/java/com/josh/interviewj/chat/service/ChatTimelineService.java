package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageView;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatTimelineService {

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final ChatProperties chatProperties;

    public ChatContextWindow getTimeline(ChatSession session) {
        int windowSize = chatProperties.getTimelineWindowSize();
        List<ChatMessage> descendingMessages = chatMessageRepository.findRecentByChatSessionId(
                session.getId(),
                PageRequest.of(0, windowSize)
        );
        long totalMessageCount = chatMessageRepository.countByChatSessionId(session.getId());
        List<ChatMessageView> messages = toViews(descendingMessages);

        return new ChatContextWindow(
                messages,
                totalMessageCount > messages.size(),
                messages.size(),
                totalMessageCount
        );
    }

    private List<ChatMessageView> toViews(List<ChatMessage> descendingMessages) {
        List<ChatMessage> orderedMessages = descendingMessages.stream()
                .sorted(java.util.Comparator.comparingInt(ChatMessage::getSequenceNumber))
                .toList();
        return orderedMessages.stream().map(this::toView).toList();
    }

    private ChatMessageView toView(ChatMessage message) {
        return new ChatMessageView(
                message.getExternalId(),
                message.getRole(),
                message.getMessageType(),
                message.getContent(),
                parseMetadata(message.getMetadata()),
                message.getCreatedAt()
        );
    }

    private JsonNode parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(metadata);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }
}
