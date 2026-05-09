package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.config.InterviewScoringProperties;
import com.josh.interviewj.interview.dto.request.SubmitInterviewAnswerRequest;
import com.josh.interviewj.interview.dto.response.SubmitInterviewAnswerResponse;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import com.josh.interviewj.interview.model.InterviewMode;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewFollowUpPolicy;
import com.josh.interviewj.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InterviewAnswerCommandService.
 *
 * <p>These tests focus on Phase 1 (read snapshot) and Phase 2 (LLM evaluation)
 * logic. Phase 3 (write with lock) is delegated to InterviewAnswerWriteService
 * and tested separately in integration tests.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewAnswerCommandServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewQuestionRepository interviewQuestionRepository;

    @Mock
    private InterviewAnswerRepository interviewAnswerRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private InterviewEvaluationService interviewEvaluationService;

    @Mock
    private InterviewQuestionGenerationService interviewQuestionGenerationService;

    @Mock
    private InterviewAnswerWriteService interviewAnswerWriteService;

    private InterviewAnswerCommandService interviewAnswerCommandService;

    @BeforeEach
    void setUp() {
        InterviewScoringService scoringService = new InterviewScoringService(new InterviewScoringProperties());
        interviewAnswerCommandService = new InterviewAnswerCommandService(
                userRepository,
                interviewSessionRepository,
                interviewQuestionRepository,
                interviewAnswerRepository,
                chatSessionRepository,
                resumeRepository,
                interviewEvaluationService,
                interviewQuestionGenerationService,
                scoringService,
                new InterviewFollowUpPolicy(scoringService),
                interviewAnswerWriteService,
                JsonMapper.builder().build()
        );
    }

    @Test
    void submitAnswer_delegatesToWriteService_afterComputingTransition() {
        Fixture fixture = fixture();

        // Phase 1: Read snapshot
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(fixture.user()));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(fixture.session().getId()))
                .thenReturn(List.of(fixture.mainQuestion(), fixture.nextMainQuestion()));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(fixture.session().getId()))
                .thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(fixture.chatSession().getExternalId()))
                .thenReturn(Optional.of(fixture.chatSession()));

        // Phase 2: LLM evaluation with heuristic fallback
        InterviewEvaluationEnvelope envelope = InterviewEvaluationEnvelope.heuristicFallback("Need clarification");
        when(interviewEvaluationService.evaluateAnswer(
                eq(fixture.mainQuestion()),
                eq("test answer"),
                eq(null),
                eq(fixture.session().getJobTitle()),
                eq(fixture.session().getContentLocale())))
                .thenReturn(envelope);
        when(interviewQuestionGenerationService.generateFollowUpQuestionContent(
                any(), any(), eq("test answer"), any(), any(Integer.class), any()))
                .thenReturn("请详细说明。");

        // Phase 3: Mock write service
        SubmitInterviewAnswerResponse mockResponse = new SubmitInterviewAnswerResponse(
                fixture.interviewId(),
                fixture.chatSession().getExternalId(),
                fixture.mainQuestion().getExternalId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(45),
                null,
                null,
                0,
                10,
                "FOLLOW_UP",
                null,
                "IN_PROGRESS"
        );
        when(interviewAnswerWriteService.writeWithLock(any(), any(), any()))
                .thenReturn(mockResponse);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("test answer");

        SubmitInterviewAnswerResponse response = interviewAnswerCommandService.submitAnswer(
                "josh",
                fixture.interviewId(),
                fixture.mainQuestion().getExternalId(),
                request
        );

        // Verify delegation to write service
        ArgumentCaptor<InterviewAnswerWriteService.AnswerSubmissionSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(InterviewAnswerWriteService.AnswerSubmissionSnapshot.class);
        ArgumentCaptor<InterviewAnswerWriteService.PrecomputedResult> precomputedCaptor =
                ArgumentCaptor.forClass(InterviewAnswerWriteService.PrecomputedResult.class);

        verify(interviewAnswerWriteService).writeWithLock(
                snapshotCaptor.capture(),
                precomputedCaptor.capture(),
                eq(request)
        );

        // Verify snapshot
        assertEquals(fixture.user(), snapshotCaptor.getValue().user());
        assertEquals(fixture.session(), snapshotCaptor.getValue().session());
        assertEquals(fixture.mainQuestion(), snapshotCaptor.getValue().question());

        // Verify precomputed result
        assertNotNull(precomputedCaptor.getValue().envelope());
        assertNotNull(precomputedCaptor.getValue().transition());
        assertEquals("FOLLOW_UP", precomputedCaptor.getValue().transition().nextAction().name());

        // Verify response
        assertEquals(mockResponse, response);
    }

    @Test
    void submitAnswer_throwsException_whenUserNotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("test");

        assertThrows(BusinessException.class, () -> interviewAnswerCommandService.submitAnswer(
                "nonexistent",
                UUID.randomUUID(),
                UUID.randomUUID(),
                request
        ));
    }

    @Test
    void submitAnswer_throwsException_whenSessionNotFound() {
        UUID interviewId = UUID.randomUUID();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(1L)
                .username("josh")
                .build()));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 1L))
                .thenReturn(Optional.empty());

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("test");

        assertThrows(BusinessException.class, () -> interviewAnswerCommandService.submitAnswer(
                "josh",
                interviewId,
                UUID.randomUUID(),
                request
        ));
    }

    @Test
    void submitAnswer_throwsException_whenQuestionNotFound() {
        UUID interviewId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();

        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(1L)
                .username("josh")
                .build()));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 1L))
                .thenReturn(Optional.of(InterviewSession.builder()
                        .id(1L)
                        .externalId(interviewId)
                        .userId(1L)
                        .build()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(1L, questionId))
                .thenReturn(Optional.empty());

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("test");

        assertThrows(BusinessException.class, () -> interviewAnswerCommandService.submitAnswer(
                "josh",
                interviewId,
                questionId,
                request
        ));
    }

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

    // ========== Phase 2 Branch Calculation Tests ==========

    /**
     * Test that mid-quality answer to main question triggers NEXT_MAIN_QUESTION.
     * 
     * <p>This verifies the Phase 2 branch calculation logic in computeEvaluationAndTransition()
     * correctly finalizes branch and moves to next main question when the score is high enough.</p>
     */
    @Test
    void submitAnswer_midSignalMainQuestion_computesNextMainQuestionTransition() {
        Fixture fixture = fixture();

        // Phase 1: Read snapshot
        setupPhase1Mocks(fixture);

        // Phase 2: Mid-quality evaluation (rubric-based, score ~74)
        InterviewEvaluationEnvelope midQualityEnvelope = InterviewEvaluationEnvelope.fromRubric(
                new com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload(
                        4, 4, 3, 4, 3, "Solid answer", List.of("Covered main approach"), List.of()
                )
        );
        when(interviewEvaluationService.evaluateAnswer(
                eq(fixture.mainQuestion()),
                eq("mid signal answer"),
                eq(120),
                eq(fixture.session().getJobTitle()),
                eq(fixture.session().getContentLocale())))
                .thenReturn(midQualityEnvelope);

        // Phase 3: Mock write service
        SubmitInterviewAnswerResponse mockResponse = createMockResponse(fixture, "NEXT_MAIN_QUESTION");
        when(interviewAnswerWriteService.writeWithLock(any(), any(), any()))
                .thenReturn(mockResponse);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("mid signal answer");
        request.setDurationSeconds(120);

        interviewAnswerCommandService.submitAnswer(
                "josh",
                fixture.interviewId(),
                fixture.mainQuestion().getExternalId(),
                request
        );

        // Verify transition computed correctly
        ArgumentCaptor<InterviewAnswerWriteService.PrecomputedResult> precomputedCaptor =
                ArgumentCaptor.forClass(InterviewAnswerWriteService.PrecomputedResult.class);
        verify(interviewAnswerWriteService).writeWithLock(any(), precomputedCaptor.capture(), any());

        InterviewAnswerWriteService.BranchTransition transition = precomputedCaptor.getValue().transition();
        assertEquals(InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION, transition.nextAction());
        assertEquals(fixture.nextMainQuestion(), transition.nextMainQuestion());
        assertNotNull(transition.branchScore());
        assertNotNull(transition.runningScore());
    }

    /**
     * Test that high-quality answer to last main question triggers INTERVIEW_COMPLETABLE.
     * 
     * <p>This verifies the finalizeBranch() logic correctly identifies when
     * there are no more unanswered main questions.</p>
     */
    @Test
    void submitAnswer_lastMainQuestion_computesInterviewCompletableTransition() {
        Fixture fixture = fixture();

        // Session with only one main question (the current one)
        InterviewSession lastQuestionSession = InterviewSession.builder()
                .id(fixture.session().getId())
                .externalId(fixture.session().getExternalId())
                .userId(fixture.session().getUserId())
                .chatSessionId(fixture.session().getChatSessionId())
                .status(InterviewStatus.IN_PROGRESS)
                .interviewMode(InterviewMode.TEXT)
                .difficultyLevel("MID")
                .jobTitle("Backend Engineer")
                .contentLocale("zh-CN")
                .mainQuestionCount(1)  // Only one question
                .currentQuestionId(fixture.mainQuestion().getId())
                .answeredMainQuestionCount(0)
                .usedFollowUpCount(0)
                .pendingFollowUpCount(0)
                .currentBranchDepth(0)
                .isCompletable(false)
                .build();

        // Phase 1: Read snapshot - only one question
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(fixture.user()));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(lastQuestionSession));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                lastQuestionSession.getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                lastQuestionSession.getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(lastQuestionSession.getId()))
                .thenReturn(List.of(fixture.mainQuestion()));  // Only one question
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(lastQuestionSession.getId()))
                .thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(fixture.chatSession().getExternalId()))
                .thenReturn(Optional.of(fixture.chatSession()));

        // Phase 2: Mid-quality evaluation (score ~60, triggers NEXT_MAIN_QUESTION)
        // Using rubric scores of 3s gives: (3*30 + 3*20 + 3*20 + 3*20 + 3*10) / 5 = 60
        InterviewEvaluationEnvelope midQualityEnvelope = InterviewEvaluationEnvelope.fromRubric(
                new com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload(
                        3, 3, 3, 3, 3, "Good answer", List.of("Clear explanation"), List.of()
                )
        );
        when(interviewEvaluationService.evaluateAnswer(
                eq(fixture.mainQuestion()),
                eq("good answer"),
                eq(120),
                eq(fixture.session().getJobTitle()),
                eq(fixture.session().getContentLocale())))
                .thenReturn(midQualityEnvelope);

        // Phase 3: Mock write service
        SubmitInterviewAnswerResponse mockResponse = new SubmitInterviewAnswerResponse(
                fixture.interviewId(),
                fixture.chatSession().getExternalId(),
                fixture.mainQuestion().getExternalId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(60),
                1,  // answered count becomes 1
                0,  // remaining is 0
                "INTERVIEW_COMPLETABLE",
                null,
                "IN_PROGRESS"
        );
        when(interviewAnswerWriteService.writeWithLock(any(), any(), any()))
                .thenReturn(mockResponse);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("good answer");
        request.setDurationSeconds(120);

        interviewAnswerCommandService.submitAnswer(
                "josh",
                fixture.interviewId(),
                fixture.mainQuestion().getExternalId(),
                request
        );

        // Verify transition computed correctly
        ArgumentCaptor<InterviewAnswerWriteService.PrecomputedResult> precomputedCaptor =
                ArgumentCaptor.forClass(InterviewAnswerWriteService.PrecomputedResult.class);
        verify(interviewAnswerWriteService).writeWithLock(any(), precomputedCaptor.capture(), any());

        InterviewAnswerWriteService.BranchTransition transition = precomputedCaptor.getValue().transition();
        assertEquals(InterviewFollowUpPolicy.NextAction.INTERVIEW_COMPLETABLE, transition.nextAction());
        assertNull(transition.nextMainQuestion());  // No next question
        assertNotNull(transition.branchScore());
        assertNotNull(transition.runningScore());
    }

    /**
     * Test that low-quality answer to main question triggers FOLLOW_UP with CLARIFY intent.
     * 
     * <p>This verifies the InterviewFollowUpPolicy decision logic is correctly applied
     * when score is below the threshold.</p>
     */
    @Test
    void submitAnswer_lowSignalMainQuestion_computesFollowUpTransition() {
        Fixture fixture = fixture();

        // Phase 1: Read snapshot
        setupPhase1Mocks(fixture);

        // Phase 2: Low-quality evaluation (heuristic fallback, score ~45)
        InterviewEvaluationEnvelope lowQualityEnvelope = InterviewEvaluationEnvelope.heuristicFallback("Need more details");
        when(interviewEvaluationService.evaluateAnswer(
                eq(fixture.mainQuestion()),
                eq("short answer"),
                eq(null),
                eq(fixture.session().getJobTitle()),
                eq(fixture.session().getContentLocale())))
                .thenReturn(lowQualityEnvelope);
        when(interviewQuestionGenerationService.generateFollowUpQuestionContent(
                any(), any(), eq("short answer"), any(), any(Integer.class), any()))
                .thenReturn("请详细说明具体步骤。");

        // Phase 3: Mock write service
        SubmitInterviewAnswerResponse mockResponse = createMockResponse(fixture, "FOLLOW_UP");
        when(interviewAnswerWriteService.writeWithLock(any(), any(), any()))
                .thenReturn(mockResponse);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("short answer");

        interviewAnswerCommandService.submitAnswer(
                "josh",
                fixture.interviewId(),
                fixture.mainQuestion().getExternalId(),
                request
        );

        // Verify transition computed correctly
        ArgumentCaptor<InterviewAnswerWriteService.PrecomputedResult> precomputedCaptor =
                ArgumentCaptor.forClass(InterviewAnswerWriteService.PrecomputedResult.class);
        verify(interviewAnswerWriteService).writeWithLock(any(), precomputedCaptor.capture(), any());

        InterviewAnswerWriteService.BranchTransition transition = precomputedCaptor.getValue().transition();
        assertEquals(InterviewFollowUpPolicy.NextAction.FOLLOW_UP, transition.nextAction());
        assertNotNull(transition.followUpQuestion());
        assertEquals(InterviewQuestionKind.FOLLOW_UP, transition.followUpQuestion().getQuestionKind());
    }

    // ========== Helper Methods ==========

    private void setupPhase1Mocks(Fixture fixture) {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(fixture.user()));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(
                fixture.interviewId(), fixture.user().getId()))
                .thenReturn(Optional.of(fixture.session()));
        when(interviewQuestionRepository.findBySessionIdAndExternalId(
                fixture.session().getId(), fixture.mainQuestion().getExternalId()))
                .thenReturn(Optional.of(fixture.mainQuestion()));
        when(interviewAnswerRepository.findBySessionIdAndQuestionId(
                fixture.session().getId(), fixture.mainQuestion().getId()))
                .thenReturn(Optional.empty());
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(fixture.session().getId()))
                .thenReturn(List.of(fixture.mainQuestion(), fixture.nextMainQuestion()));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(fixture.session().getId()))
                .thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(fixture.chatSession().getExternalId()))
                .thenReturn(Optional.of(fixture.chatSession()));
    }

    private SubmitInterviewAnswerResponse createMockResponse(Fixture fixture, String nextAction) {
        return new SubmitInterviewAnswerResponse(
                fixture.interviewId(),
                fixture.chatSession().getExternalId(),
                fixture.mainQuestion().getExternalId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(70),
                BigDecimal.valueOf(70),
                BigDecimal.valueOf(70),
                1,
                9,
                nextAction,
                null,
                "IN_PROGRESS"
        );
    }

    private record Fixture(
            User user,
            UUID interviewId,
            InterviewSession session,
            ChatSession chatSession,
            InterviewQuestion mainQuestion,
            InterviewQuestion nextMainQuestion
    ) {
    }
}