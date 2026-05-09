package com.josh.interviewj.chat.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageView;
import com.josh.interviewj.chat.dto.ChatSessionSummaryView;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatMessageTimelineResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionItemResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads knowledge base scoped chat sessions and timelines.
 */
@Service
@RequiredArgsConstructor
public class ChatSessionQueryService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatTimelineService chatTimelineService;
    private final ChatSessionAccessPolicy chatSessionAccessPolicy;
    private final ObjectMapper objectMapper;

    /**
     * Lists chat sessions that belong to the target knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param page requested page index
     * @param size requested page size
     * @param status optional session status filter
     * @return paged chat session response
     */
    public KnowledgeBaseChatSessionListResponse listKnowledgeBaseSessions(
            String username,
            UUID kbExternalId,
            int page,
            int size,
            ChatSessionStatus status
    ) {
        User user = knowledgeBaseAccessService.requireUser(username);
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReadableKnowledgeBase(username, kbExternalId);
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<ChatSession> sessionPage = status == null
                ? chatSessionRepository.findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdOrderByUpdatedAtDesc(
                user.getId(),
                ChatDomainType.RAG_QA,
                ChatDomainRefType.KNOWLEDGE_BASE,
                knowledgeBase.getExternalId(),
                pageRequest
        )
                : chatSessionRepository.findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdAndStatusOrderByUpdatedAtDesc(
                user.getId(),
                ChatDomainType.RAG_QA,
                ChatDomainRefType.KNOWLEDGE_BASE,
                knowledgeBase.getExternalId(),
                status,
                pageRequest
        );

        return new KnowledgeBaseChatSessionListResponse(
                sessionPage.getContent().stream()
                        .map(this::toSessionItem)
                        .toList(),
                sessionPage.getNumber(),
                sessionPage.getSize(),
                sessionPage.getTotalElements(),
                sessionPage.getTotalPages(),
                sessionPage.isFirst(),
                sessionPage.isLast(),
                sessionPage.isEmpty()
        );
    }

    /**
     * Returns the readable timeline for one chat session under the target knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param sessionExternalId chat session external id
     * @return timeline response
     */
    public KnowledgeBaseChatMessageTimelineResponse getKnowledgeBaseTimeline(
            String username,
            UUID kbExternalId,
            UUID sessionExternalId
    ) {
        User user = knowledgeBaseAccessService.requireUser(username);
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReadableKnowledgeBase(username, kbExternalId);
        ChatSession session = chatSessionRepository.findByExternalIdAndUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalId(
                        sessionExternalId,
                        user.getId(),
                        ChatDomainType.RAG_QA,
                        ChatDomainRefType.KNOWLEDGE_BASE,
                        knowledgeBase.getExternalId()
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "Session not found"));
        chatSessionAccessPolicy.assertReadable(session);

        ChatContextWindow timeline = chatTimelineService.getTimeline(session);
        return new KnowledgeBaseChatMessageTimelineResponse(
                timeline.messages().stream().map(this::toMessageItem).toList(),
                timeline.truncated(),
                timeline.returnedCount(),
                timeline.totalMessageCount()
        );
    }

    /**
     * Maps a chat session entity to the list item shape exposed by the API.
     *
     * @param session session entity
     * @return response item
     */
    private KnowledgeBaseChatSessionItemResponse toSessionItem(ChatSession session) {
        return new KnowledgeBaseChatSessionItemResponse(
                session.getExternalId(),
                session.getTitle() == null ? "" : session.getTitle(),
                session.getLastMessagePreview(),
                session.getMessageCount(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    /**
     * Maps one timeline message view to the public API shape.
     *
     * @param message timeline message
     * @return response item
     */
    private KnowledgeBaseChatMessageTimelineResponse.MessageItem toMessageItem(ChatMessageView message) {
        return new KnowledgeBaseChatMessageTimelineResponse.MessageItem(
                message.messageId(),
                message.role().name(),
                message.messageType().name(),
                message.content(),
                toMetadataMap(message.metadata()),
                message.createdAt()
        );
    }

    /**
     * Converts persisted JSON metadata into a mutable map for API serialization.
     *
     * @param metadata persisted metadata node
     * @return metadata map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMetadataMap(tools.jackson.databind.JsonNode metadata) {
        if (metadata == null || metadata.isNull() || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(metadata, Map.class);
    }
}
