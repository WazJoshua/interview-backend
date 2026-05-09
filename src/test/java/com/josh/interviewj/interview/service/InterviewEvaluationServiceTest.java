package com.josh.interviewj.interview.service;

import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewEvaluationServiceTest {

    @Mock
    private AiOperationGateway aiOperationGateway;

    @Mock
    private InterviewQuestion question;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    private InterviewEvaluationService evaluationService;
    private InterviewLlmJsonParser jsonParser;

    private static final String QUESTION_CONTENT = "Tell me about your experience with distributed systems.";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        jsonParser = new InterviewLlmJsonParser(objectMapper);
        evaluationService = new InterviewEvaluationService(
                aiOperationGateway,
                jsonParser,
                interviewSessionRepository
        );
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                8L,
                "INTERVIEW_SESSION",
                "session-1",
                "interview_answer_evaluation",
                java.util.List.of("INTERVIEW_CREDITS"),
                java.util.Map.of()
        ));

        when(question.getId()).thenReturn(1L);
        when(question.getQuestionContent()).thenReturn(QUESTION_CONTENT);
        when(question.getSessionId()).thenReturn(5L);
        when(interviewSessionRepository.findById(5L)).thenReturn(java.util.Optional.of(InterviewSession.builder()
                .id(5L)
                .externalId(java.util.UUID.randomUUID())
                .userId(8L)
                .chatSessionId(java.util.UUID.randomUUID())
                .build()));
    }

    @Test
    void evaluateAnswer_LlmSuccess_ReturnsRubricEnvelope() {
        String jsonRubric = """
                {
                  "answerRelevance": 4,
                  "specificity": 5,
                  "reasoning": 4,
                  "technicalJudgment": 4,
                  "communication": 5,
                  "overallComment": "Excellent answer with concrete examples.",
                  "evidence": ["Explained concrete trade-offs"],
                  "risks": ["Could add clearer rollback details"]
                }
                """;
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        jsonRubric,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        InterviewEvaluationEnvelope envelope = evaluationService.evaluateAnswer(
                question,
                "I designed a distributed caching system that handles 100k QPS...",
                60,
                "Senior Backend Engineer",
                ContentLocale.EN_US.getTag()
        );

        assertFalse(envelope.fallbackUsed());
        assertEquals("llm", envelope.mode());
        assertEquals("interview-eval-v1", envelope.schemaVersion());
        assertNotNull(envelope.rubric());
        assertEquals(4, envelope.rubric().answerRelevance());
        assertEquals(5, envelope.rubric().specificity());
        assertEquals(4, envelope.rubric().reasoning());
        assertEquals(4, envelope.rubric().technicalJudgment());
        assertEquals(5, envelope.communication());
        assertEquals(1, envelope.evidence().size());
        assertEquals(1, envelope.risks().size());
        assertEquals("Excellent answer with concrete examples.", envelope.overallComment());

        // Verify weighted score calculation
        assertNotNull(envelope.calculateWeightedScore());
        assertEquals(86.00, envelope.calculateWeightedScore().doubleValue(), 0.01);
    }

    @Test
    void evaluateAnswer_LlmFailure_ReturnsHeuristicFallback() {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_001", "LLM service unavailable"));

        InterviewEvaluationEnvelope envelope = evaluationService.evaluateAnswer(
                question,
                "I have experience with microservices architecture.",
                30,
                "Backend Engineer",
                ContentLocale.EN_US.getTag()
        );

        assertTrue(envelope.fallbackUsed());
        assertEquals("heuristic_fallback", envelope.mode());
        assertEquals("interview-eval-v1", envelope.schemaVersion());
        assertNotNull(envelope.rubric());
        assertNull(envelope.rubric().answerRelevance());
        assertNull(envelope.rubric().specificity());
        assertNull(envelope.rubric().reasoning());
        assertNull(envelope.rubric().technicalJudgment());
        assertNull(envelope.communication());
        assertTrue(envelope.evidence().isEmpty());
        assertTrue(envelope.risks().isEmpty());
        assertNotNull(envelope.overallComment());
    }

    @Test
    void evaluateAnswer_CircuitBreakerOpen_ReturnsHeuristicFallback() {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_002", "Circuit breaker is open"));

        InterviewEvaluationEnvelope envelope = evaluationService.evaluateAnswer(
                question,
                "I worked on a distributed system project.",
                45,
                "Backend Engineer",
                ContentLocale.ZH_CN.getTag()
        );

        assertTrue(envelope.fallbackUsed());
        assertEquals("heuristic_fallback", envelope.mode());
        assertNotNull(envelope.overallComment());
    }

    @Test
    void evaluateAnswer_InvalidJsonSchema_ReturnsHeuristicFallback() {
        String invalidJson = """
                {
                  "answerRelevance": 6,
                  "specificity": 5,
                  "reasoning": 4,
                  "technicalJudgment": 4,
                  "communication": 4,
                  "overallComment": "Invalid score out of range.",
                  "evidence": [],
                  "risks": []
                }
                """;
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        invalidJson,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        InterviewEvaluationEnvelope envelope = evaluationService.evaluateAnswer(
                question,
                "Some answer content.",
                30,
                "Engineer",
                ContentLocale.EN_US.getTag()
        );

        assertTrue(envelope.fallbackUsed());
        assertEquals("heuristic_fallback", envelope.mode());
    }

    @Test
    void evaluateAnswer_InvalidJsonSchema_RecordsFallbackRecoveredUsage() {
        String invalidJson = """
                {
                  "answerRelevance": 6,
                  "specificity": 5,
                  "reasoning": 4,
                  "technicalJudgment": 4,
                  "communication": 4,
                  "overallComment": "Invalid score out of range.",
                  "evidence": [],
                  "risks": []
                }
                """;
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        invalidJson,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        evaluationService.evaluateAnswer(
                question,
                "Some answer content.",
                30,
                "Engineer",
                ContentLocale.EN_US.getTag()
        );

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void evaluateAnswer_WeightedScore_CalculatesCorrectly() {
        String jsonRubric = """
                {
                  "answerRelevance": 5,
                  "specificity": 5,
                  "reasoning": 5,
                  "technicalJudgment": 5,
                  "communication": 5,
                  "overallComment": "Perfect score.",
                  "evidence": ["Clear and concrete"],
                  "risks": []
                }
                """;
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        jsonRubric,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        InterviewEvaluationEnvelope envelope = evaluationService.evaluateAnswer(
                question,
                "Perfect answer.",
                60,
                "Senior Engineer",
                ContentLocale.EN_US.getTag()
        );

        // Weighted: ((5*30 + 5*20 + 5*20 + 5*20 + 5*10) / 5) = 100
        assertEquals(100.0, envelope.calculateWeightedScore().doubleValue(), 0.01);
    }

    @Test
    void evaluateAnswer_BackwardCompatible_ReturnsInterviewEvaluationResult() {
        String jsonRubric = """
                {
                  "answerRelevance": 3,
                  "specificity": 4,
                  "reasoning": 3,
                  "technicalJudgment": 4,
                  "communication": 3,
                  "overallComment": "Good effort.",
                  "evidence": ["Covered main approach"],
                  "risks": ["Missing deeper trade-off analysis"]
                }
                """;
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        jsonRubric,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        InterviewEvaluationService.InterviewEvaluationResult result =
                evaluationService.evaluateAnswer(question, "Some answer.", 30);

        assertNotNull(result.score());
        assertNotNull(result.summary());
        assertNotNull(result.evaluationDetailsJson());
        assertTrue(result.evaluationDetailsJson().contains("\"schemaVersion\":\"interview-eval-v1\""));
        assertTrue(result.evaluationDetailsJson().contains("\"mode\":\"llm\""));
        assertTrue(result.evaluationDetailsJson().contains("\"rubric\""));
        assertTrue(result.evaluationDetailsJson().contains("\"evidence\""));
        assertTrue(result.evaluationDetailsJson().contains("\"risks\""));
        assertTrue(result.evaluationDetailsJson().contains("Good effort"));
    }

    @Test
    void evaluateAnswer_BackwardCompatibleFallback_ReturnsInterviewEvaluationResult() {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_001", "LLM unavailable"));

        InterviewEvaluationService.InterviewEvaluationResult result =
                evaluationService.evaluateAnswer(question, "Some answer with enough length to score.", 30);

        assertNotNull(result.score());
        assertNotNull(result.summary());
        assertNotNull(result.evaluationDetailsJson());
        assertTrue(result.evaluationDetailsJson().contains("heuristic_fallback"));
        assertTrue(result.evaluationDetailsJson().contains("fallbackUsed\":true"));
        assertTrue(result.evaluationDetailsJson().contains("\"rubric\""));
    }

    @Test
    void evaluateAnswer_RepeatedCallsUseDifferentBusinessOperationIds() {
        when(aiOperationGateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        """
                                {
                                  "answerRelevance": 4,
                                  "specificity": 4,
                                  "reasoning": 4,
                                  "technicalJudgment": 4,
                                  "communication": 4,
                                  "overallComment": "Stable.",
                                  "evidence": [],
                                  "risks": []
                                }
                                """,
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        evaluationService.evaluateAnswer(question, "answer one", 30, "Engineer", ContentLocale.EN_US.getTag());
        evaluationService.evaluateAnswer(question, "answer two", 30, "Engineer", ContentLocale.EN_US.getTag());

        ArgumentCaptor<BusinessOperationContext> operationCaptor = ArgumentCaptor.forClass(BusinessOperationContext.class);
        verify(aiOperationGateway, org.mockito.Mockito.times(2)).prepareOperation(operationCaptor.capture());
        assertEquals(2, operationCaptor.getAllValues().size());
        assertNotEquals(
                operationCaptor.getAllValues().get(0).businessOperationId(),
                operationCaptor.getAllValues().get(1).businessOperationId()
        );

        ArgumentCaptor<AiInvocationContext> invocationCaptor = ArgumentCaptor.forClass(AiInvocationContext.class);
        verify(aiOperationGateway, org.mockito.Mockito.times(2)).executeInvocation(any(), invocationCaptor.capture(), any());
        assertNotEquals(
                invocationCaptor.getAllValues().get(0).invocationId(),
                invocationCaptor.getAllValues().get(1).invocationId()
        );
    }
}
