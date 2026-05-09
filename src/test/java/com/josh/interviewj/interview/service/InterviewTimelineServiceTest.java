package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatMessageView;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatTimelineService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.response.InterviewMessageTimelineResponse;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewTimelineServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatTimelineService chatTimelineService;

    private InterviewTimelineService interviewTimelineService;

    @BeforeEach
    void setUp() {
        interviewTimelineService = new InterviewTimelineService(
                userRepository,
                interviewSessionRepository,
                chatSessionRepository,
                chatTimelineService,
                JsonMapper.builder().build()
        );
    }

    @Test
    void getTimeline_PreservesInterviewMessageTypesAndTopLevelIdentities() {
        UUID interviewId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        InterviewSession session = InterviewSession.builder()
                .id(21L)
                .externalId(interviewId)
                .userId(11L)
                .chatSessionId(chatSessionId)
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(31L)
                .externalId(chatSessionId)
                .userId(11L)
                .build();
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("questionId", UUID.randomUUID().toString());
        metadata.put("questionKind", "MAIN");
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 11L))
                .thenReturn(Optional.of(session));
        when(chatSessionRepository.findByExternalId(chatSessionId)).thenReturn(Optional.of(chatSession));
        when(chatTimelineService.getTimeline(chatSession)).thenReturn(new ChatContextWindow(
                List.of(new ChatMessageView(
                        UUID.randomUUID(),
                        ChatRole.ASSISTANT,
                        ChatMessageType.INTERVIEW_QUESTION,
                        "Tell me about your last migration.",
                        metadata,
                        LocalDateTime.parse("2026-03-25T10:00:00")
                )),
                false,
                1,
                1
        ));

        InterviewMessageTimelineResponse response = interviewTimelineService.getTimeline("josh", interviewId);

        assertEquals(interviewId, response.interviewId());
        assertEquals(chatSessionId, response.chatSessionId());
        assertEquals(1, response.messages().size());
        assertEquals("INTERVIEW_QUESTION", response.messages().getFirst().messageType());
        assertEquals("MAIN", response.messages().getFirst().metadata().get("questionKind"));
        assertEquals(1, response.returnedCount());
        assertEquals(1L, response.totalMessageCount());
    }

    @Test
    void getTimeline_DeletedInterviewThrowsNotFound() {
        UUID interviewId = UUID.randomUUID();
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 11L))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> interviewTimelineService.getTimeline("josh", interviewId));
    }
}
