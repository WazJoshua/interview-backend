package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.request.SubmitInterviewAnswerRequest;
import com.josh.interviewj.interview.dto.response.SubmitInterviewAnswerResponse;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewMode;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewConcurrencyGuard;
import com.josh.interviewj.interview.support.InterviewFollowUpPolicy;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InterviewAnswerWriteService.
 *
 * <p>These tests cover Phase 3 (write with lock) logic including:
 * <ul>
 *   <li>Concurrency guard: repeated submission conflict, session abort detection</li>
 *   <li>State reload: chat session reload instead of using stale snapshot</li>
 *   <li>Write ordering: correct order of message save, answer save, follow-up creation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewAnswerWriteServiceTest {

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewQuestionRepository interviewQuestionRepository;

    @Mock
    private InterviewAnswerRepository interviewAnswerRepository;

    @Mock
    private InterviewReportRepository interviewReportRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatEventRecorder chatEventRecorder;

    @Mock
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Mock
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    private InterviewAnswerWriteService interviewAnswerWriteService;

    @BeforeEach
    void setUp() {
        interviewAnswerWriteService = new InterviewAnswerWriteService(
                interviewSessionRepository,
                interviewQuestionRepository,
                interviewAnswerRepository,
                interviewReportRepository,
                chatSessionRepository,
                chatMessageRepository,
                new InterviewConcurrencyGuard(),
                JsonMapper.builder().build()
        );
        ReflectionTestUtils.setField(interviewAnswerWriteService, "chatEventRecorder", chatEventRecorder);
        ReflectionTestUtils.setField(interviewAnswerWriteService, "interviewWebSocketEventPublisher", interviewWebSocketEventPublisher);
        ReflectionTestUtils.setField(interviewAnswerWriteService, "interviewWebSocketPayloadFactory", interviewWebSocketPayloadFactory);
    }

    @Test
    void writeWithLock_WhenSessionIsAbortedAfterSnapshot_RejectsSubmission() {
        Fixture fixture = fixture();
        InterviewSession abortedSession = InterviewSession.builder()
                .id(fixture.session().getId())
                .externalId(fixture.session().getExternalId())
                .userId(fixture.session().getUserId())
                .chatSessionId(fixture.session().getChatSessionId())
                .status(InterviewStatus.ABORTED)
                .currentQuestionId(fixture.session().getCurrentQuestionId())
                .jobTitle(fixture.session().getJobTitle())
                .contentLocale(fixture.session().getContentLocale())
                .build();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(abortedSession));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                abortedSession.getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                abortedSession.getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());

        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithFollowUp();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        BusinessException exception = assertThrows(BusinessException.class, () ->
                interviewAnswerWriteService.writeWithLock(snapshot, precomputed, fixture.request()));

        assertEquals("INTERVIEW_007", exception.getErrorCode());
        verify(interviewAnswerRepository, never()).save(any(InterviewAnswer.class));
        verify(chatMessageRepository, never()).saveAll(any());
    }

    @Test
    void writeWithLock_RepeatedSubmissionForAnsweredQuestion_ThrowsConflict() {
        Fixture fixture = fixture();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.of(InterviewAnswer.builder()
                        .id(301L)
                        .externalId(UUID.randomUUID())
                        .sessionId(fixture.session().getId())
                        .questionId(fixture.mainQuestion().getId())
                        .build()));

        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithFollowUp();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        assertThrows(BusinessException.class, () ->
                interviewAnswerWriteService.writeWithLock(snapshot, precomputed, fixture.request()));
    }

    @Test
    void writeWithLock_ReloadsChatSessionStateInsteadOfUsingSnapshot() {
        Fixture fixture = fixture();
        ChatSession latestChatSession = ChatSession.builder()
                .id(fixture.chatSession().getId())
                .externalId(fixture.chatSession().getExternalId())
                .userId(fixture.chatSession().getUserId())
                .status(ChatSessionStatus.ACTIVE)
                .nextMessageSequence(10)
                .messageCount(9)
                .lastMessagePreview("report summary")
                .build();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(chatSessionRepository.findByIdForUpdate(fixture.chatSession().getId()))
                .thenReturn(Optional.of(latestChatSession));
        when(interviewReportRepository.findBySessionId(fixture.session().getId()))
                .thenReturn(Optional.empty());
        when(interviewAnswerRepository.save(any(InterviewAnswer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Use a transition that moves to next main question (no follow-up)
        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithNextMainQuestion();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        interviewAnswerWriteService.writeWithLock(snapshot, precomputed, fixture.request());

        ArgumentCaptor<ChatSession> chatSessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionRepository).save(chatSessionCaptor.capture());
        ChatSession persisted = chatSessionCaptor.getValue();
        // Verify that sequence was calculated from the reloaded session (10 + 3 messages = 13)
        assertEquals(13, persisted.getNextMessageSequence());
        assertEquals(12, persisted.getMessageCount());
    }

    @Test
    void writeWithLock_LowSignalMainQuestion_CreatesClarifyFollowUpInCorrectOrder() {
        Fixture fixture = fixture();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(chatSessionRepository.findByIdForUpdate(fixture.chatSession().getId()))
                .thenReturn(Optional.of(fixture.chatSession()));
        when(interviewReportRepository.findBySessionId(fixture.session().getId()))
                .thenReturn(Optional.empty());
        when(interviewAnswerRepository.save(any(InterviewAnswer.class))).thenAnswer(invocation -> {
            InterviewAnswer answer = invocation.getArgument(0);
            answer.setId(301L);
            if (answer.getExternalId() == null) {
                answer.setExternalId(UUID.randomUUID());
            }
            return answer;
        });
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            long id = 401L;
            for (ChatMessage message : messages) {
                message.setId(id++);
                if (message.getExternalId() == null) {
                    message.setExternalId(UUID.randomUUID());
                }
            }
            return messages;
        });
        when(interviewQuestionRepository.save(any(InterviewQuestion.class))).thenAnswer(invocation -> {
            InterviewQuestion followUp = invocation.getArgument(0);
            followUp.setId(201L);
            if (followUp.getExternalId() == null) {
                followUp.setExternalId(UUID.randomUUID());
            }
            return followUp;
        });
        when(interviewQuestionRepository.incrementSequenceNumbersFrom(fixture.session().getId(), 2)).thenReturn(1);
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewWebSocketPayloadFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(mock(InterviewWebSocketPayloadFactory.InterviewEventPayload.class));

        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithFollowUp();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        SubmitInterviewAnswerResponse response = interviewAnswerWriteService.writeWithLock(
                snapshot, precomputed, fixture.request());

        assertEquals("FOLLOW_UP", response.nextAction());
        assertNull(response.runningScore());
        assertNull(response.branchScore());
        assertEquals(0, response.answeredMainQuestionCount());
        assertEquals("CLARIFY", response.followUpQuestion().followUpIntent());
        assertEquals(1, response.followUpQuestion().branchDepth());

        // Verify follow-up question was created with correct sequence
        ArgumentCaptor<InterviewQuestion> followUpCaptor = ArgumentCaptor.forClass(InterviewQuestion.class);
        verify(interviewQuestionRepository).save(followUpCaptor.capture());
        assertEquals(2, followUpCaptor.getValue().getSequenceNumber());

        // Verify session state updated
        assertEquals(1, fixture.session().getCurrentBranchDepth());
        assertEquals(1, fixture.session().getPendingFollowUpCount());
        assertEquals(1, fixture.session().getUsedFollowUpCount());

        // Verify increment was called
        verify(interviewQuestionRepository).incrementSequenceNumbersFrom(fixture.session().getId(), 2);

        // Verify write ordering: messages -> answer -> increment -> follow-up question
        InOrder inOrder = inOrder(chatMessageRepository, interviewAnswerRepository, interviewQuestionRepository);
        inOrder.verify(chatMessageRepository).saveAll(any());
        inOrder.verify(interviewAnswerRepository).save(any(InterviewAnswer.class));
        inOrder.verify(interviewQuestionRepository).incrementSequenceNumbersFrom(fixture.session().getId(), 2);
        inOrder.verify(interviewQuestionRepository).save(any(InterviewQuestion.class));
    }

    @Test
    void writeWithLock_MidSignalMainQuestion_FinalizesBranchAndMovesToNextMainQuestion() {
        Fixture fixture = fixture();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(chatSessionRepository.findByIdForUpdate(fixture.chatSession().getId()))
                .thenReturn(Optional.of(fixture.chatSession()));
        when(interviewReportRepository.findBySessionId(fixture.session().getId()))
                .thenReturn(Optional.empty());
        when(interviewAnswerRepository.save(any(InterviewAnswer.class))).thenAnswer(invocation -> {
            InterviewAnswer answer = invocation.getArgument(0);
            answer.setId(301L);
            if (answer.getExternalId() == null) {
                answer.setExternalId(UUID.randomUUID());
            }
            return answer;
        });
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewWebSocketPayloadFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(mock(InterviewWebSocketPayloadFactory.InterviewEventPayload.class));

        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithNextMainQuestion();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        SubmitInterviewAnswerResponse response = interviewAnswerWriteService.writeWithLock(
                snapshot, precomputed, fixture.request());

        assertEquals("NEXT_MAIN_QUESTION", response.nextAction());
        // Weighted score: ((4*30 + 4*20 + 3*20 + 4*20 + 3*10) / 5) = 74.00
        assertEquals(BigDecimal.valueOf(74.0), response.branchScore());
        assertEquals(BigDecimal.valueOf(74.0), response.runningScore());
        assertEquals(1, response.answeredMainQuestionCount());

        // Verify session updated
        assertEquals(fixture.nextMainQuestion().getId(), fixture.session().getCurrentQuestionId());
        assertEquals(0, fixture.session().getCurrentBranchDepth());
        assertEquals(0, fixture.session().getPendingFollowUpCount());
    }

    @Test
    void writeWithLock_EmitsCorrectEvents() {
        Fixture fixture = fixture();

        when(interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(chatSessionRepository.findByIdForUpdate(fixture.chatSession().getId()))
                .thenReturn(Optional.of(fixture.chatSession()));
        when(interviewReportRepository.findBySessionId(fixture.session().getId()))
                .thenReturn(Optional.empty());
        when(interviewAnswerRepository.save(any(InterviewAnswer.class))).thenAnswer(invocation -> {
            InterviewAnswer answer = invocation.getArgument(0);
            answer.setId(301L);
            answer.setExternalId(UUID.randomUUID());
            return answer;
        });
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            long id = 401L;
            for (ChatMessage message : messages) {
                message.setId(id++);
                message.setExternalId(UUID.randomUUID());
            }
            return messages;
        });
        when(interviewQuestionRepository.save(any(InterviewQuestion.class))).thenAnswer(invocation -> {
            InterviewQuestion question = invocation.getArgument(0);
            question.setId(201L);
            if (question.getExternalId() == null) {
                question.setExternalId(UUID.randomUUID());
            }
            return question;
        });
        when(interviewQuestionRepository.incrementSequenceNumbersFrom(fixture.session().getId(), 2)).thenReturn(1);
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewSessionRepository.save(any(InterviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewWebSocketPayloadFactory.create(any(), any(), any(), any(), any()))
                .thenReturn(mock(InterviewWebSocketPayloadFactory.InterviewEventPayload.class));

        InterviewAnswerWriteService.PrecomputedResult precomputed = fixture.precomputedResultWithFollowUp();
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot = fixture.snapshot();

        interviewAnswerWriteService.writeWithLock(snapshot, precomputed, fixture.request());

        // Verify chat events were recorded
        ArgumentCaptor<ChatEventRecorder.ChatEventDraft> eventCaptor = ArgumentCaptor.forClass(ChatEventRecorder.ChatEventDraft.class);
        verify(chatEventRecorder, times(4)).recordAfterCommit(eventCaptor.capture());

        List<ChatEventRecorder.ChatEventDraft> events = eventCaptor.getAllValues();
        assertEquals("ANSWER_ACCEPTED", events.get(0).eventType());
        assertEquals("EVALUATION_READY", events.get(1).eventType());
        assertEquals("FOLLOW_UP_CREATED", events.get(2).eventType());
        assertEquals("INTERVIEW_PROGRESS_UPDATED", events.get(3).eventType());

        // Verify websocket events were published
        verify(interviewWebSocketEventPublisher, times(4)).publishAfterCommit(any());
    }

    // ========== Fixture ==========

    private Fixture fixture() {
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
                .status(InterviewStatus.IN_PROGRESS)
                .interviewMode(InterviewMode.TEXT)
                .difficultyLevel("MID")
                .jobTitle("Backend Engineer")
                .contentLocale("zh-CN")
                .mainQuestionCount(10)
                .currentQuestionId(101L)
                .answeredMainQuestionCount(0)
                .usedFollowUpCount(0)
                .pendingFollowUpCount(0)
                .currentBranchDepth(0)
                .isCompletable(false)
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(31L)
                .externalId(chatSessionId)
                .userId(11L)
                .status(ChatSessionStatus.ACTIVE)
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewQuestion mainQuestion = InterviewQuestion.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .sessionId(21L)
                .questionKind(InterviewQuestionKind.MAIN)
                .questionType("BASIC")
                .questionContent("Tell me about a recent backend project.")
                .difficulty(2)
                .estimatedMinutes(3)
                .sequenceNumber(1)
                .branchDepth(0)
                .build();
        InterviewQuestion nextMainQuestion = InterviewQuestion.builder()
                .id(102L)
                .externalId(UUID.randomUUID())
                .sessionId(21L)
                .questionKind(InterviewQuestionKind.MAIN)
                .questionType("SKILL")
                .questionContent("How do you trace a memory leak?")
                .difficulty(3)
                .estimatedMinutes(3)
                .sequenceNumber(2)
                .branchDepth(0)
                .build();
        return new Fixture(user, interviewId, session, chatSession, mainQuestion, nextMainQuestion);
    }

    private record Fixture(
            User user,
            UUID interviewId,
            InterviewSession session,
            ChatSession chatSession,
            InterviewQuestion mainQuestion,
            InterviewQuestion nextMainQuestion
    ) {
        InterviewAnswerWriteService.AnswerSubmissionSnapshot snapshot() {
            return new InterviewAnswerWriteService.AnswerSubmissionSnapshot(
                    user(),
                    session(),
                    mainQuestion(),
                    null, // existingAnswer
                    chatSession(),
                    List.of(mainQuestion(), nextMainQuestion()),
                    List.of(mainQuestion(), nextMainQuestion()).stream()
                            .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity())),
                    List.of(),
                    null // resumeContent
            );
        }

        InterviewAnswerWriteService.PrecomputedResult precomputedResultWithFollowUp() {
            InterviewEvaluationEnvelope envelope = InterviewEvaluationEnvelope.heuristicFallback("Need clarification");
            InterviewQuestion followUp = InterviewQuestion.builder()
                    .sessionId(session().getId())
                    .externalId(UUID.randomUUID())
                    .questionKind(InterviewQuestionKind.FOLLOW_UP)
                    .followUpIntent(InterviewFollowUpIntent.CLARIFY)
                    .parentQuestionId(mainQuestion().getId())
                    .branchDepth(1)
                    .sequenceNumber(2)
                    .questionType("BASIC")
                    .questionContent("请详细说明具体步骤。")
                    .difficulty(3)
                    .estimatedMinutes(2)
                    .build();

            InterviewAnswerWriteService.BranchTransition transition = new InterviewAnswerWriteService.BranchTransition(
                    InterviewFollowUpPolicy.NextAction.FOLLOW_UP,
                    followUp,
                    null,
                    null,
                    null
            );

            return new InterviewAnswerWriteService.PrecomputedResult(
                    envelope,
                    BigDecimal.valueOf(45),
                    "{}",
                    transition
            );
        }

        InterviewAnswerWriteService.PrecomputedResult precomputedResultWithNextMainQuestion() {
            InterviewEvaluationEnvelope envelope = InterviewEvaluationEnvelope.fromRubric(
                    new com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload(
                            4, 4, 3, 4, 3, "Solid answer", List.of("Covered main approach"), List.of()
                    )
            );

            InterviewAnswerWriteService.BranchTransition transition = new InterviewAnswerWriteService.BranchTransition(
                    InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION,
                    null,
                    nextMainQuestion(),
                    BigDecimal.valueOf(74.00),
                    BigDecimal.valueOf(74.00)
            );

            return new InterviewAnswerWriteService.PrecomputedResult(
                    envelope,
                    BigDecimal.valueOf(74.00),
                    "{}",
                    transition
            );
        }

        SubmitInterviewAnswerRequest request() {
            SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
            request.setAnswerContent("test answer");
            return request;
        }
    }
}