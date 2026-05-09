package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.request.CompleteInterviewRequest;
import com.josh.interviewj.interview.dto.response.DeleteInterviewResponse;
import com.josh.interviewj.interview.dto.response.InterviewLifecycleResponse;
import com.josh.interviewj.interview.model.InterviewCompletionReason;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewLifecycleServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private InterviewReportService interviewReportService;

    @Mock
    private ExecutorService virtualThreadExecutor;

    @Mock
    private ChatEventRecorder chatEventRecorder;

    @Mock
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Mock
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    private InterviewLifecycleService interviewLifecycleService;

    @BeforeEach
    void setUp() {
        interviewLifecycleService = new InterviewLifecycleService(
                userRepository,
                interviewSessionRepository,
                chatSessionRepository,
                interviewReportService,
                virtualThreadExecutor
        );
        ReflectionTestUtils.setField(interviewLifecycleService, "chatEventRecorder", chatEventRecorder);
        ReflectionTestUtils.setField(interviewLifecycleService, "interviewWebSocketEventPublisher", interviewWebSocketEventPublisher);
        ReflectionTestUtils.setField(interviewLifecycleService, "interviewWebSocketPayloadFactory", interviewWebSocketPayloadFactory);
    }

    @Test
    void endInterview_MarksSessionCompletedAndStartsReportGeneration() {
        User user = user();
        InterviewSession session = inProgressSession();
        ChatSession chatSession = ChatSession.builder()
                .id(31L)
                .externalId(session.getChatSessionId())
                .userId(user.getId())
                .status(ChatSessionStatus.ACTIVE)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(41L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(chatSessionRepository.findByExternalIdForUpdate(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(interviewReportService.prepareReportGeneration(session)).thenReturn(report);
        when(interviewSessionRepository.save(session)).thenReturn(session);
        when(chatSessionRepository.save(chatSession)).thenReturn(chatSession);
        when(interviewWebSocketPayloadFactory.create(eq(session.getExternalId()), eq(session.getChatSessionId()), any(), eq(null), any()))
                .thenAnswer(invocation -> new InterviewWebSocketPayloadFactory.InterviewEventPayload(
                        UUID.randomUUID(),
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        LocalDateTime.now(),
                        invocation.getArgument(3),
                        invocation.getArgument(4)
                ));

        CompleteInterviewRequest request = new CompleteInterviewRequest();
        request.setCompletionReason(InterviewCompletionReason.USER_EARLY_END);

        InterviewLifecycleResponse response = interviewLifecycleService.endInterview("josh", session.getExternalId(), request);

        assertEquals("COMPLETED", response.status());
        assertEquals("USER_EARLY_END", response.completionReason());
        assertEquals("GENERATING", response.reportStatus());
        assertEquals(InterviewStatus.COMPLETED, session.getStatus());
        assertEquals(ChatSessionStatus.COMPLETED, chatSession.getStatus());
        verify(virtualThreadExecutor).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(interviewReportService, never()).generateReport(session.getId());

        ArgumentCaptor<ChatEventRecorder.ChatEventDraft> chatEventCaptor =
                ArgumentCaptor.forClass(ChatEventRecorder.ChatEventDraft.class);
        verify(chatEventRecorder, org.mockito.Mockito.times(2)).recordAfterCommit(chatEventCaptor.capture());
        List<String> eventTypes = chatEventCaptor.getAllValues().stream()
                .map(ChatEventRecorder.ChatEventDraft::eventType)
                .toList();
        assertEquals(List.of("INTERVIEW_COMPLETED", "REPORT_GENERATING"), eventTypes);

        ArgumentCaptor<InterviewWebSocketPayloadFactory.InterviewEventPayload> websocketCaptor =
                ArgumentCaptor.forClass(InterviewWebSocketPayloadFactory.InterviewEventPayload.class);
        verify(interviewWebSocketEventPublisher, org.mockito.Mockito.times(2)).publishAfterCommit(websocketCaptor.capture());
        List<String> websocketEventTypes = websocketCaptor.getAllValues().stream()
                .map(InterviewWebSocketPayloadFactory.InterviewEventPayload::eventType)
                .toList();
        assertEquals(List.of("INTERVIEW_COMPLETED", "REPORT_GENERATING"), websocketEventTypes);
    }

    @Test
    void endInterview_CompletedAllRequiresSessionToBeActuallyCompletable() {
        User user = user();
        InterviewSession session = inProgressSession();
        session.setMainQuestionCount(5);
        session.setAnsweredMainQuestionCount(3);
        session.setCurrentQuestionId(99L);
        session.setIsCompletable(false);
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));

        CompleteInterviewRequest request = new CompleteInterviewRequest();
        request.setCompletionReason(InterviewCompletionReason.COMPLETED_ALL);

        assertThrows(BusinessException.class, () -> interviewLifecycleService.endInterview("josh", session.getExternalId(), request));
    }

    @Test
    void deleteInterview_InProgressSessionAbortsThenSoftDeletes() {
        User user = user();
        InterviewSession session = inProgressSession();
        ChatSession chatSession = ChatSession.builder()
                .id(31L)
                .externalId(session.getChatSessionId())
                .userId(user.getId())
                .status(ChatSessionStatus.ACTIVE)
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(chatSessionRepository.findByExternalIdForUpdate(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(interviewSessionRepository.save(session)).thenReturn(session);
        when(chatSessionRepository.save(chatSession)).thenReturn(chatSession);
        when(interviewWebSocketPayloadFactory.create(eq(session.getExternalId()), eq(session.getChatSessionId()), any(), eq(null), any()))
                .thenAnswer(invocation -> new InterviewWebSocketPayloadFactory.InterviewEventPayload(
                        UUID.randomUUID(),
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        LocalDateTime.now(),
                        invocation.getArgument(3),
                        invocation.getArgument(4)
                ));

        DeleteInterviewResponse response = interviewLifecycleService.deleteInterview("josh", session.getExternalId());

        assertEquals(session.getExternalId(), response.interviewId());
        assertEquals(true, response.deleted());
        assertEquals(InterviewStatus.ABORTED, session.getStatus());
        assertEquals(InterviewCompletionReason.ABORTED, session.getCompletionReason());
        assertNotNull(session.getEndTime());
        assertNotNull(session.getDeletedAt());
        assertEquals(ChatSessionStatus.ABORTED, chatSession.getStatus());
        verify(interviewReportService, never()).prepareReportGeneration(any());

        ArgumentCaptor<ChatEventRecorder.ChatEventDraft> chatEventCaptor =
                ArgumentCaptor.forClass(ChatEventRecorder.ChatEventDraft.class);
        verify(chatEventRecorder).recordAfterCommit(chatEventCaptor.capture());
        assertEquals("INTERVIEW_ABORTED", chatEventCaptor.getValue().eventType());

        ArgumentCaptor<InterviewWebSocketPayloadFactory.InterviewEventPayload> websocketCaptor =
                ArgumentCaptor.forClass(InterviewWebSocketPayloadFactory.InterviewEventPayload.class);
        verify(interviewWebSocketEventPublisher).publishAfterCommit(websocketCaptor.capture());
        assertEquals("INTERVIEW_ABORTED", websocketCaptor.getValue().eventType());
    }

    @Test
    void abortInterview_WhenChatSessionBecameActiveAfterScan_SkipsAbort() {
        InterviewSession session = inProgressSession();
        ChatSession chatSession = ChatSession.builder()
                .id(31L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .status(ChatSessionStatus.ACTIVE)
                .lastMessageAt(LocalDateTime.parse("2026-03-28T09:45:00"))
                .build();
        LocalDateTime cutoff = LocalDateTime.parse("2026-03-28T09:30:00");
        when(interviewSessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        when(chatSessionRepository.findByExternalIdForUpdate(session.getChatSessionId())).thenReturn(Optional.of(chatSession));

        boolean aborted = interviewLifecycleService.abortInterview(session.getId(), cutoff, false);

        assertEquals(false, aborted);
        verify(interviewSessionRepository, never()).save(session);
        verify(chatSessionRepository, never()).save(chatSession);
        verify(chatEventRecorder, never()).recordAfterCommit(any());
        verify(interviewWebSocketEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void deleteInterview_NonRunningSessionSoftDeletesIt() {
        User user = user();
        InterviewSession session = inProgressSession();
        session.setStatus(InterviewStatus.COMPLETED);
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(interviewSessionRepository.save(session)).thenReturn(session);

        DeleteInterviewResponse response = interviewLifecycleService.deleteInterview("josh", session.getExternalId());

        assertEquals(session.getExternalId(), response.interviewId());
        assertEquals(true, response.deleted());
        assertEquals(LocalDateTime.class, session.getDeletedAt().getClass());
    }

    private User user() {
        return User.builder()
                .id(11L)
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
    }

    private InterviewSession inProgressSession() {
        return InterviewSession.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .chatSessionId(UUID.randomUUID())
                .userId(11L)
                .status(InterviewStatus.IN_PROGRESS)
                .build();
    }
}
