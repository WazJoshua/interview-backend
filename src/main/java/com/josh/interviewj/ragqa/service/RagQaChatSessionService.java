package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageDraft;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatContextWindowService;
import com.josh.interviewj.chat.service.ChatSessionAccessPolicy;
import com.josh.interviewj.chat.service.ChatWriteService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagQaChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatContextWindowService chatContextWindowService;
    private final ChatWriteService chatWriteService;
    private final ChatSessionAccessPolicy chatSessionAccessPolicy;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    public RagQaChatSessionContext resolveContext(String username, KnowledgeBase knowledgeBase, UUID requestedChatSessionId) {
        if (requestedChatSessionId == null) {
            return new RagQaChatSessionContext(newSessionDraft(knowledgeBase), new ChatContextWindow(List.of(), false, 0, 0));
        }

        ChatSession existingSession = chatSessionRepository
                .findByExternalIdAndUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalId(
                        requestedChatSessionId,
                        knowledgeBase.getUserId(),
                        ChatDomainType.RAG_QA,
                        ChatDomainRefType.KNOWLEDGE_BASE,
                        knowledgeBase.getExternalId()
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "Session not found"));
        chatSessionAccessPolicy.assertWritable(existingSession);

        if (!chatProperties.isRagqaIncludeRecentTurnContext()) {
            return new RagQaChatSessionContext(existingSession, new ChatContextWindow(List.of(), false, 0, 0));
        }

        ChatContextWindow recentWindow = chatContextWindowService.getRecentWindow(
                existingSession,
                chatProperties.getDefaultContextMessageLimit(),
                Set.of(ChatRole.USER, ChatRole.ASSISTANT),
                Set.of(ChatMessageType.TEXT, ChatMessageType.ANSWER),
                chatProperties.getDefaultContextBudget()
        );
        return new RagQaChatSessionContext(existingSession, recentWindow);
    }

    public ChatTurnWriteResult persistTurn(
            ChatSession session,
            String question,
            String answer,
            Double confidence,
            List<KnowledgeBaseQueryResponse.Source> sourceSnapshot,
            int retrievedChunkCount
    ) {
        ChatMessageDraft userDraft = new ChatMessageDraft(
                ChatRole.USER,
                ChatMessageType.TEXT,
                question,
                "{}",
                null,
                null
        );
        ChatMessageDraft assistantDraft = new ChatMessageDraft(
                ChatRole.ASSISTANT,
                ChatMessageType.ANSWER,
                answer,
                buildAssistantMetadata(confidence, sourceSnapshot, retrievedChunkCount),
                null,
                null
        );
        return chatWriteService.appendTurn(session, userDraft, assistantDraft);
    }

    private ChatSession newSessionDraft(KnowledgeBase knowledgeBase) {
        return ChatSession.builder()
                .userId(knowledgeBase.getUserId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .title("")
                .build();
    }

    private String buildAssistantMetadata(
            Double confidence,
            List<KnowledgeBaseQueryResponse.Source> sourceSnapshot,
            int retrievedChunkCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("confidence", confidence);
        metadata.put("sourceCount", sourceSnapshot == null ? 0 : sourceSnapshot.size());
        metadata.put("retrievedChunkCount", retrievedChunkCount);
        metadata.put("sourceSnapshot", sourceSnapshot == null ? List.of() : sourceSnapshot.stream()
                .map(source -> Map.of(
                        "documentId", source.getDocumentId(),
                        "documentName", source.getDocumentName(),
                        "chunkIndex", source.getChunkIndex()
                ))
                .toList());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            return "{}";
        }
    }

    public record RagQaChatSessionContext(
            ChatSession session,
            ChatContextWindow recentWindow
    ) {
    }
}
