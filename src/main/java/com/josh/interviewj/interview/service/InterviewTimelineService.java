package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageView;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatTimelineService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.response.InterviewMessageTimelineResponse;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewTimelineService {

    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatTimelineService chatTimelineService;
    private final ObjectMapper objectMapper;

    public InterviewMessageTimelineResponse getTimeline(String username, UUID interviewId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        ChatSession chatSession = chatSessionRepository.findByExternalId(session.getChatSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));

        ChatContextWindow timeline = chatTimelineService.getTimeline(chatSession);
        return new InterviewMessageTimelineResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                timeline.messages().stream().map(this::toMessageItem).toList(),
                timeline.truncated(),
                timeline.returnedCount(),
                timeline.totalMessageCount()
        );
    }

    private InterviewMessageTimelineResponse.MessageItem toMessageItem(ChatMessageView message) {
        return new InterviewMessageTimelineResponse.MessageItem(
                message.messageId(),
                message.role().name(),
                message.messageType().name(),
                message.content(),
                toMetadataMap(message.metadata()),
                message.createdAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMetadataMap(tools.jackson.databind.JsonNode metadata) {
        if (metadata == null || metadata.isNull() || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(metadata, Map.class);
    }
}
