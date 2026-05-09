package com.josh.interviewj.interview;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
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
import com.josh.interviewj.interview.service.InterviewAnswerCommandService;
import com.josh.interviewj.interview.service.InterviewEvaluationService;
import com.josh.interviewj.interview.service.InterviewQuestionGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration test for interview answer submission flow.
 * 
 * <p>This test verifies that the submitAnswer flow correctly handles
 * the transaction boundary when LLM calls fail and fallback is used.</p>
 * 
 * <p>See issue: docs/issues/2026-03-28-interview-answer-submit-no-active-transaction-after-llm-fallback.md</p>
 */
@SpringBootTest
class InterviewAnswerSubmitFlowIT extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewSessionRepository interviewSessionRepository;

    @Autowired
    private InterviewQuestionRepository interviewQuestionRepository;

    @Autowired
    private InterviewAnswerRepository interviewAnswerRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private InterviewAnswerCommandService interviewAnswerCommandService;

    @MockitoBean
    private InterviewEvaluationService interviewEvaluationService;

    @MockitoBean
    private InterviewQuestionGenerationService interviewQuestionGenerationService;

    private User testUser;
    private InterviewSession testSession;
    private ChatSession testChatSession;
    private InterviewQuestion testQuestion;
    private InterviewQuestion nextQuestion;

    @BeforeEach
    @Transactional
    void setUpTestData() {
        // Create test user
        String username = "it-answer-user-" + UUID.randomUUID();
        testUser = userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("password_hash")
                .locale("zh-CN")
                .build());

        // Create chat session
        UUID chatSessionExternalId = UUID.randomUUID();
        UUID interviewExternalId = UUID.randomUUID();  // will be used for session
        testChatSession = chatSessionRepository.save(ChatSession.builder()
                .externalId(chatSessionExternalId)
                .userId(testUser.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(interviewExternalId)
                .status(ChatSessionStatus.ACTIVE)
                .nextMessageSequence(1)
                .messageCount(0)
                .build());

        // Create interview session (use the same externalId as domainRef)
        testSession = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(interviewExternalId)
                .userId(testUser.getId())
                .chatSessionId(testChatSession.getExternalId())
                .status(InterviewStatus.IN_PROGRESS)
                .interviewMode(InterviewMode.TEXT)
                .difficultyLevel("MID")
                .jobTitle("Backend Engineer")
                .contentLocale("zh-CN")
                .mainQuestionCount(2)
                .answeredMainQuestionCount(0)
                .usedFollowUpCount(0)
                .pendingFollowUpCount(0)
                .currentBranchDepth(0)
                .isCompletable(false)
                .build());

        // Create first main question
        testQuestion = interviewQuestionRepository.save(InterviewQuestion.builder()
                .externalId(UUID.randomUUID())
                .sessionId(testSession.getId())
                .questionKind(InterviewQuestionKind.MAIN)
                .questionType("BASIC")
                .questionContent("Tell me about your recent backend project.")
                .difficulty(2)
                .estimatedMinutes(3)
                .sequenceNumber(1)
                .branchDepth(0)
                .build());

        // Create second main question
        nextQuestion = interviewQuestionRepository.save(InterviewQuestion.builder()
                .externalId(UUID.randomUUID())
                .sessionId(testSession.getId())
                .questionKind(InterviewQuestionKind.MAIN)
                .questionType("SKILL")
                .questionContent("How do you handle concurrency?")
                .difficulty(3)
                .estimatedMinutes(3)
                .sequenceNumber(2)
                .branchDepth(0)
                .build());

        // Set current question
        testSession.setCurrentQuestionId(testQuestion.getId());
        interviewSessionRepository.save(testSession);

        // Mock follow-up generation to return deterministic fallback
        when(interviewQuestionGenerationService.generateFollowUpQuestionContent(
                any(), any(), anyString(), any(), anyInt(), any()))
                .thenReturn("请详细说明具体步骤。");
    }

    /**
     * Test that submitAnswer works correctly when LLM fallback is used.
     * 
     * <p>This test verifies that after LLM timeout and fallback,
     * the pessimistic lock query in writeWithLock() executes within
     * a valid transaction context.</p>
     * 
     * <p>Before fix: This would throw "No active transaction" because
     * writeWithLock() is called via self-invocation, bypassing Spring AOP proxy.</p>
     * 
     * <p>After fix: Transaction should be active when pessimistic lock query runs.</p>
     */
    @Test
    void submitAnswer_withLlmFallback_completesSuccessfully() {
        // Mock LLM evaluation to use heuristic fallback (simulating LLM timeout scenario)
        InterviewEvaluationEnvelope heuristicFallback = InterviewEvaluationEnvelope.heuristicFallback("需要更详细的回答");
        when(interviewEvaluationService.evaluateAnswer(any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(heuristicFallback);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("我做了一个 Spring Boot 项目");
        request.setDurationSeconds(60);

        // This should complete without "No active transaction" error
        SubmitInterviewAnswerResponse response = interviewAnswerCommandService.submitAnswer(
                testUser.getUsername(),
                testSession.getExternalId(),
                testQuestion.getExternalId(),
                request
        );

        // Verify response
        assertNotNull(response);
        assertEquals(testSession.getExternalId(), response.interviewId());
        assertEquals(testQuestion.getExternalId(), response.questionId());
        assertNotNull(response.answerId());
        assertNotNull(response.userMessageId());
        assertNotNull(response.evaluationMessageId());
        
        // Verify answer was persisted
        InterviewSession updatedSession = interviewSessionRepository
                .findByExternalIdAndUserIdAndDeletedAtIsNull(testSession.getExternalId(), testUser.getId())
                .orElseThrow();
        
        // Should have created follow-up (because low signal heuristic fallback)
        assertEquals("FOLLOW_UP", response.nextAction());
        
        // Verify answer record exists
        List<?> answers = interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(updatedSession.getId());
        assertEquals(1, answers.size());
    }

    /**
     * Test that submitAnswer with high-quality answer completes successfully.
     * 
     * <p>This test verifies the happy path with a good quality answer
     * that triggers transition to next main question.</p>
     */
    @Test
    void submitAnswer_withHighQualityAnswer_completesSuccessfully() {
        // Mock high-quality evaluation (rubric-based)
        InterviewEvaluationEnvelope highQualityEnvelope = InterviewEvaluationEnvelope.fromRubric(
                new com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload(
                        4, 4, 4, 4, 4, "优秀回答", List.of("清晰完整"), List.of()
                )
        );
        when(interviewEvaluationService.evaluateAnswer(eq(testQuestion), anyString(), any(), anyString(), anyString()))
                .thenReturn(highQualityEnvelope);

        SubmitInterviewAnswerRequest request = new SubmitInterviewAnswerRequest();
        request.setAnswerContent("我设计了一个分布式系统，使用 Redis 缓存，PostgreSQL 持久化，并实现了幂等性保证");
        request.setDurationSeconds(120);

        SubmitInterviewAnswerResponse response = interviewAnswerCommandService.submitAnswer(
                testUser.getUsername(),
                testSession.getExternalId(),
                testQuestion.getExternalId(),
                request
        );

        assertNotNull(response);
        assertNotNull(response.answerId());
        assertNotNull(response.evaluationMessageId());

        // Verify answer was persisted
        List<?> answers = interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(testSession.getId());
        assertEquals(1, answers.size());
    }

}