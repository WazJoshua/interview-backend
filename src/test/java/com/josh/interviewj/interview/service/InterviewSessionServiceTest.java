package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.request.CreateInterviewRequest;
import com.josh.interviewj.interview.dto.response.CreateInterviewResponse;
import com.josh.interviewj.interview.dto.response.InterviewStartResponse;
import com.josh.interviewj.interview.model.InterviewMode;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewSessionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewQuestionRepository interviewQuestionRepository;

    @Mock
    private InterviewReportRepository interviewReportRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private InterviewQuestionGenerationService interviewQuestionGenerationService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private InterviewSessionService interviewSessionService;

    @BeforeEach
    void setUp() {
        interviewSessionService = new InterviewSessionService(
                userRepository,
                resumeRepository,
                interviewSessionRepository,
                interviewQuestionRepository,
                interviewReportRepository,
                chatSessionRepository,
                chatMessageRepository,
                interviewQuestionGenerationService,
                JsonMapper.builder().build(),
                transactionTemplate
        );
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void createInterview_CreatesInterviewAndChatSessionWithoutGeneratingQuestions() {
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        Resume resume = Resume.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .userId(11L)
                .fileName("resume.pdf")
                .fileUrl("mock://resume.pdf")
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resume.getExternalId(), 11L))
                .thenReturn(Optional.of(resume));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setResumeId(resume.getExternalId());
        request.setJobTitle("Java Backend");
        request.setJobDescription("Build distributed systems");
        request.setDurationMinutes(30);
        request.setInterviewMode(InterviewMode.TEXT);

        CreateInterviewResponse response = interviewSessionService.createInterview("josh", request);

        ArgumentCaptor<ChatSession> chatSessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        ArgumentCaptor<InterviewSession> sessionCaptor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(chatSessionRepository).save(chatSessionCaptor.capture());
        verify(interviewSessionRepository).save(sessionCaptor.capture());
        verify(interviewQuestionGenerationService, never()).generateMainQuestions(any());

        ChatSession savedChatSession = chatSessionCaptor.getValue();
        InterviewSession savedSession = sessionCaptor.getValue();
        assertEquals(savedSession.getExternalId(), savedChatSession.getDomainRefExternalId());
        assertEquals(savedChatSession.getExternalId(), savedSession.getChatSessionId());
        assertEquals(ChatDomainType.INTERVIEW, savedChatSession.getDomainType());
        assertEquals(ChatDomainRefType.INTERVIEW_SESSION, savedChatSession.getDomainRefType());
        assertEquals(InterviewStatus.CREATED, savedSession.getStatus());
        assertEquals("MID", response.difficultyLevel());
        assertEquals("NOT_READY", response.reportStatus());
        assertEquals(savedSession.getExternalId(), response.interviewId());
        assertEquals(savedChatSession.getExternalId(), response.chatSessionId());
    }

    @Test
    void startInterview_CreatedSessionGeneratesQuestionsAndFirstTimelineMessage() {
        UUID interviewId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        InterviewSession session = InterviewSession.builder()
                .id(31L)
                .externalId(interviewId)
                .userId(11L)
                .chatSessionId(chatSessionId)
                .jobTitle("Java Backend")
                .difficultyLevel("MID")
                .interviewMode(InterviewMode.TEXT)
                .status(InterviewStatus.CREATED)
                .createdAt(LocalDateTime.parse("2026-03-25T10:00:00"))
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(chatSessionId)
                .userId(11L)
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(interviewId)
                .status(ChatSessionStatus.CREATED)
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewQuestion firstQuestion = InterviewQuestion.builder()
                .id(51L)
                .externalId(UUID.randomUUID())
                .sessionId(31L)
                .questionType("BASIC")
                .questionContent("Tell me about your last backend migration.")
                .difficulty(2)
                .estimatedMinutes(3)
                .sequenceNumber(1)
                .questionKind(InterviewQuestionKind.MAIN)
                .build();
        InterviewQuestion secondQuestion = InterviewQuestion.builder()
                .id(52L)
                .externalId(UUID.randomUUID())
                .sessionId(31L)
                .questionType("SKILL")
                .questionContent("How do you debug JVM memory issues?")
                .difficulty(3)
                .estimatedMinutes(3)
                .sequenceNumber(2)
                .questionKind(InterviewQuestionKind.MAIN)
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 11L)).thenReturn(Optional.of(session));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(interviewId, 11L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.findByExternalId(chatSessionId)).thenReturn(Optional.of(chatSession));
        when(interviewQuestionGenerationService.generateMainQuestions(any(InterviewSession.class), any(), any()))
                .thenReturn(List.of(firstQuestion, secondQuestion));
        when(interviewReportRepository.findBySessionId(31L)).thenReturn(Optional.empty());
        when(interviewQuestionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(61L);
            if (message.getExternalId() == null) {
                message.setExternalId(UUID.randomUUID());
            }
            return message;
        });
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InterviewStartResponse response = interviewSessionService.startInterview("josh", interviewId);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        ChatMessage firstPrompt = messageCaptor.getValue();

        assertEquals(ChatRole.ASSISTANT, firstPrompt.getRole());
        assertEquals(ChatMessageType.INTERVIEW_QUESTION, firstPrompt.getMessageType());
        assertEquals(1, firstPrompt.getSequenceNumber());
        assertEquals(InterviewStatus.IN_PROGRESS, session.getStatus());
        assertEquals(Integer.valueOf(2), session.getMainQuestionCount());
        assertEquals(firstQuestion.getId(), session.getCurrentQuestionId());
        assertEquals(firstQuestion.getExternalId(), response.currentQuestion().questionId());
        assertEquals("MAIN", response.currentQuestion().questionKind());
        assertEquals(2, response.questionProgress().mainQuestionCount());
        InOrder inOrder = inOrder(chatMessageRepository, interviewQuestionRepository);
        inOrder.verify(chatMessageRepository).save(any(ChatMessage.class));
        inOrder.verify(interviewQuestionRepository).saveAll(any());
    }

    @Test
    void startInterview_RepeatedStartThrowsConflict() {
        UUID interviewId = UUID.randomUUID();
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        InterviewSession session = InterviewSession.builder()
                .id(31L)
                .externalId(interviewId)
                .userId(11L)
                .chatSessionId(UUID.randomUUID())
                .status(InterviewStatus.IN_PROGRESS)
                .interviewMode(InterviewMode.TEXT)
                .difficultyLevel("MID")
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(interviewId, 11L)).thenReturn(Optional.of(session));

        assertThrows(BusinessException.class, () -> interviewSessionService.startInterview("josh", interviewId));
    }

    @Test
    void getInterviewDetail_AbortedSessionStillVisible() {
        UUID interviewId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        User user = User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
        InterviewSession session = InterviewSession.builder()
                .id(31L)
                .externalId(interviewId)
                .userId(11L)
                .chatSessionId(chatSessionId)
                .jobTitle("Java Backend")
                .difficultyLevel("MID")
                .interviewMode(InterviewMode.TEXT)
                .status(InterviewStatus.ABORTED)
                .completionReason(com.josh.interviewj.interview.model.InterviewCompletionReason.ABORTED)
                .endTime(LocalDateTime.parse("2026-03-28T09:00:00"))
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 11L))
                .thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(31L)).thenReturn(Optional.empty());

        var response = interviewSessionService.getInterviewDetail("josh", interviewId);

        assertEquals(interviewId, response.interviewId());
        assertEquals("ABORTED", response.status());
        assertEquals("ABORTED", response.completionReason());
        assertEquals("NOT_READY", response.reportStatus());
    }
}
