package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageView;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatContextWindowService {

    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public ChatContextWindow getRecentWindow(
            ChatSession session,
            int messageLimit,
            Set<ChatRole> allowedRoles,
            Set<ChatMessageType> allowedMessageTypes,
            int budget
    ) {
        List<ChatMessage> descendingMessages = chatMessageRepository.findRecentByChatSessionId(
                session.getId(),
                PageRequest.of(0, messageLimit)
        );
        return buildWindow(descendingMessages, allowedRoles, allowedMessageTypes, budget);
    }

    public ChatContextWindow getWindowBeforeMessage(
            ChatSession session,
            UUID anchorMessageExternalId,
            int messageLimit,
            Set<ChatRole> allowedRoles,
            Set<ChatMessageType> allowedMessageTypes,
            int budget
    ) {
        ChatMessage anchorMessage = chatMessageRepository.findByChatSessionIdAndExternalId(session.getId(), anchorMessageExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "Anchor message not found"));
        List<ChatMessage> descendingMessages = chatMessageRepository.findWindowUpToSequenceNumber(
                session.getId(),
                anchorMessage.getSequenceNumber(),
                PageRequest.of(0, messageLimit)
        );
        return buildWindow(descendingMessages, allowedRoles, allowedMessageTypes, budget);
    }

    private ChatContextWindow buildWindow(
            List<ChatMessage> descendingMessages,
            Set<ChatRole> allowedRoles,
            Set<ChatMessageType> allowedMessageTypes,
            int budget
    ) {
        List<ChatMessage> orderedMessages = descendingMessages.stream()
                .sorted(Comparator.comparingInt(ChatMessage::getSequenceNumber))
                .toList();
        List<ChatMessage> filteredMessages = orderedMessages.stream()
                .filter(message -> allowedRoles == null || allowedRoles.isEmpty() || allowedRoles.contains(message.getRole()))
                .filter(message -> allowedMessageTypes == null || allowedMessageTypes.isEmpty() || allowedMessageTypes.contains(message.getMessageType()))
                .toList();
        List<ChatMessage> budgetedMessages = applyBudget(filteredMessages, budget);
        List<ChatMessageView> views = budgetedMessages.stream().map(this::toView).toList();

        return new ChatContextWindow(
                views,
                budgetedMessages.size() < orderedMessages.size(),
                views.size(),
                filteredMessages.size()
        );
    }

    private List<ChatMessage> applyBudget(List<ChatMessage> messages, int budget) {
        if (budget <= 0) {
            return messages;
        }

        int remaining = budget;
        List<ChatMessage> selectedMessages = new ArrayList<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            int cost = message.getEstimatedTokenCount() != null && message.getEstimatedTokenCount() > 0
                    ? message.getEstimatedTokenCount()
                    : message.getContent().length();
            if (!selectedMessages.isEmpty() && cost > remaining) {
                break;
            }
            if (selectedMessages.isEmpty() && cost > budget) {
                selectedMessages.add(message);
                break;
            }
            if (cost <= remaining) {
                selectedMessages.add(message);
                remaining -= cost;
            }
        }
        selectedMessages.sort(Comparator.comparingInt(ChatMessage::getSequenceNumber));
        return selectedMessages;
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
