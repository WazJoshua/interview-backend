package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.interview.dto.response.InterviewReportResponse;
import com.josh.interviewj.interview.llm.dto.InterviewReportPayload;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.config.InterviewScoringProperties;
import com.josh.interviewj.interview.model.InterviewCompletionReason;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewOverallScoreCalculator;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewReportServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewReportRepository interviewReportRepository;

    @Mock
    private InterviewQuestionRepository interviewQuestionRepository;

    @Mock
    private InterviewAnswerRepository interviewAnswerRepository;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private AiOperationGateway aiOperationGateway;

    @Mock
    private TransactionTemplate transactionTemplate;

    private InterviewReportService interviewReportService;
    private InterviewLlmJsonParser jsonParser;
    private InterviewOverallScoreCalculator overallScoreCalculator;
    private InterviewScoringService interviewScoringService;

    @BeforeEach
    void setUp() {
        JsonMapper objectMapper = JsonMapper.builder().build();
        jsonParser = new InterviewLlmJsonParser(objectMapper);
        overallScoreCalculator = new InterviewOverallScoreCalculator();
        interviewScoringService = new InterviewScoringService(new InterviewScoringProperties());
        interviewReportService = new InterviewReportService(
                userRepository,
                interviewSessionRepository,
                interviewReportRepository,
                interviewQuestionRepository,
                interviewAnswerRepository,
                chatSessionRepository,
                chatMessageRepository,
                objectMapper,
                aiOperationGateway,
                jsonParser,
                overallScoreCalculator,
                interviewScoringService,
                transactionTemplate
        );
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                1L,
                "INTERVIEW_REPORT",
                "report-1",
                "interview_report_generation",
                List.of("INTERVIEW_CREDITS"),
                Map.of()
        ));
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void getReport_NoReportLifecycleYetReturnsNotReadyWithoutReportId() {
        User user = user("josh");
        InterviewSession session = session();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        InterviewReportResponse response = interviewReportService.getReport("josh", session.getExternalId());

        assertNull(response.reportId());
        assertEquals("NOT_READY", response.reportStatus());
    }

    @Test
    void getReport_WhenInterviewAbortedWithoutReport_ReturnsNotReadyWithAbortedCompletionReason() {
        User user = user("josh");
        InterviewSession session = session();
        session.setStatus(InterviewStatus.ABORTED);
        session.setCompletionReason(InterviewCompletionReason.ABORTED);
        session.setEndTime(LocalDateTime.parse("2026-03-28T09:00:00"));
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        InterviewReportResponse response = interviewReportService.getReport("josh", session.getExternalId());

        assertNull(response.reportId());
        assertEquals("NOT_READY", response.reportStatus());
        assertEquals("ABORTED", response.completionReason());
        assertNull(response.overallScore());
        assertNull(response.runningScore());
        assertNull(response.generatedAt());
        assertNull(response.summary());
    }

    @Test
    void getReport_FailedReportExposesFailureFields() {
        User user = user("josh");
        InterviewSession session = session();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.FAILED)
                .failureCode("REPORT_GENERATION_FAILED")
                .failureMessage("Report generation failed. Recovery may retry from FAILED to GENERATING.")
                .failedAt(LocalDateTime.parse("2026-03-25T10:40:00"))
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));

        InterviewReportResponse response = interviewReportService.getReport("josh", session.getExternalId());

        assertEquals(report.getExternalId(), response.reportId());
        assertEquals("FAILED", response.reportStatus());
        assertEquals("REPORT_GENERATION_FAILED", response.failureCode());
        assertTrue(response.retryable());
        assertEquals(report.getFailedAt(), response.failedAt());
        // Failed reports should not expose scores
        assertNull(response.overallScore());
        assertNull(response.runningScore());
    }

    @Test
    void getReport_GeneratingReportDoesNotExposeGeneratedAt() {
        User user = user("josh");
        InterviewSession session = session();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .generatedAt(LocalDateTime.parse("2026-03-25T10:35:00"))
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(session.getExternalId(), user.getId()))
                .thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));

        InterviewReportResponse response = interviewReportService.getReport("josh", session.getExternalId());

        assertEquals("GENERATING", response.reportStatus());
        assertNull(response.generatedAt());
    }

    @Test
    void retryFailedReportGeneration_KeepsCompletedSessionAndResetsStatusToGenerating() {
        InterviewSession session = session();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.FAILED)
                .failureCode("REPORT_GENERATION_FAILED")
                .failureMessage("failed")
                .failedAt(LocalDateTime.parse("2026-03-25T10:40:00"))
                .build();
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewReportRepository.save(report)).thenReturn(report);

        boolean retried = interviewReportService.retryFailedReportGeneration(session);

        assertTrue(retried);
        assertEquals(InterviewStatus.COMPLETED, session.getStatus());
        assertEquals(InterviewReportStatus.GENERATING, report.getStatus());
        assertNull(report.getFailureCode());
        assertNull(report.getFailureMessage());
        assertNull(report.getFailedAt());
    }

    @Test
    void generateReport_GeneratingReportTransitionsToReady() {
        InterviewSession session = session();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();

        String llmResponse = """
                {
                  "summary": "Strong technical candidate with good communication skills.",
                  "strengths": ["Problem-solving", "Technical depth"],
                  "weaknesses": ["Could elaborate more on trade-offs"],
                  "improvementSuggestions": ["Practice system design questions"],
                  "skillAssessment": {
                    "Backend Development": {
                      "level": "advanced",
                      "evidence": "Demonstrated strong understanding of distributed systems"
                    }
                  }
                }
                """;

        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId())).thenReturn(List.of(
                InterviewQuestion.builder()
                        .id(51L)
                        .questionKind(InterviewQuestionKind.MAIN)
                        .questionContent("Tell me about your experience")
                        .sequenceNumber(1)
                        .build()
        ));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewReportRepository.save(report)).thenReturn(report);
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        llmResponse,
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        interviewReportService.generateReport(session.getId());

        assertEquals(InterviewReportStatus.READY, report.getStatus());
        assertNotNull(report.getOverallScore());
        assertEquals("Strong technical candidate with good communication skills.", report.getSummary());
        // Quality scores should be null as per design
        assertNull(report.getContentQualityScore());
        assertNull(report.getExpressionQualityScore());
        assertNull(report.getLogicQualityScore());
    }

    @Test
    void generateReport_Success_RecordsUsage() {
        InterviewSession session = session();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();
        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId())).thenReturn(List.of(
                InterviewQuestion.builder()
                        .id(51L)
                        .questionKind(InterviewQuestionKind.MAIN)
                        .questionContent("Tell me about your experience")
                        .sequenceNumber(1)
                        .build()
        ));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewReportRepository.save(report)).thenReturn(report);
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse("""
                        {
                          "summary": "Summary",
                          "strengths": ["Depth"],
                          "weaknesses": ["Gap"],
                          "improvementSuggestions": ["Practice"],
                          "skillAssessment": {}
                        }
                        """, "mock", "mock", new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L))));

        interviewReportService.generateReport(session.getId());

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void generateReport_RetryUsesDifferentBusinessOperationIds() {
        when(aiOperationGateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        InterviewSession session = session();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();

        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId())).thenReturn(List.of(
                InterviewQuestion.builder()
                        .id(51L)
                        .questionKind(InterviewQuestionKind.MAIN)
                        .questionContent("Tell me about your experience")
                        .sequenceNumber(1)
                        .build()
        ));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewReportRepository.save(report)).thenReturn(report);
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse("""
                        {
                          "summary": "Summary",
                          "strengths": ["Depth"],
                          "weaknesses": ["Gap"],
                          "improvementSuggestions": ["Practice"],
                          "skillAssessment": {}
                        }
                        """, "mock", "mock", new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L))));

        interviewReportService.generateReport(session.getId());
        report.setStatus(InterviewReportStatus.GENERATING);
        interviewReportService.generateReport(session.getId());

        ArgumentCaptor<BusinessOperationContext> operationCaptor = ArgumentCaptor.forClass(BusinessOperationContext.class);
        verify(aiOperationGateway, org.mockito.Mockito.times(2)).prepareOperation(operationCaptor.capture());
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

    @Test
    void generateReport_UsesFinalizedBranchScoresForInstabilityPenalty() {
        InterviewSession session = session();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();

        InterviewQuestion main1 = InterviewQuestion.builder()
                .id(51L)
                .questionKind(InterviewQuestionKind.MAIN)
                .sequenceNumber(1)
                .questionContent("Main 1")
                .build();
        InterviewQuestion deepDive1 = InterviewQuestion.builder()
                .id(52L)
                .questionKind(InterviewQuestionKind.FOLLOW_UP)
                .followUpIntent(InterviewFollowUpIntent.DEEP_DIVE)
                .parentQuestionId(51L)
                .branchDepth(1)
                .sequenceNumber(2)
                .questionContent("Deep dive 1")
                .build();
        InterviewQuestion main2 = InterviewQuestion.builder()
                .id(53L)
                .questionKind(InterviewQuestionKind.MAIN)
                .sequenceNumber(3)
                .questionContent("Main 2")
                .build();

        InterviewAnswer mainAnswer1 = InterviewAnswer.builder()
                .questionId(51L)
                .evaluationScore(BigDecimal.valueOf(80))
                .answerContent("Answer 1")
                .build();
        InterviewAnswer deepDiveAnswer1 = InterviewAnswer.builder()
                .questionId(52L)
                .evaluationScore(BigDecimal.valueOf(100))
                .answerContent("Deep dive answer")
                .build();
        InterviewAnswer mainAnswer2 = InterviewAnswer.builder()
                .questionId(53L)
                .evaluationScore(BigDecimal.valueOf(60))
                .answerContent("Answer 2")
                .build();

        BigDecimal branch1 = interviewScoringService.finalizeDeepDiveBranch(
                mainAnswer1.getEvaluationScore(),
                List.of(deepDiveAnswer1.getEvaluationScore())
        );
        BigDecimal branch2 = interviewScoringService.finalizeMainOnly(mainAnswer2.getEvaluationScore());
        BigDecimal runningScore = interviewScoringService.calculateRunningScore(List.of(branch1, branch2));
        session.setRunningScore(runningScore);

        String llmResponse = """
                {
                  "summary": "Summary",
                  "strengths": ["Depth"],
                  "weaknesses": ["Speed"],
                  "improvementSuggestions": ["Improve structure"],
                  "skillAssessment": {
                    "Backend Development": {
                      "level": "advanced",
                      "evidence": "Evidence"
                    }
                  }
                }
                """;

        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId()))
                .thenReturn(List.of(main1, deepDive1, main2));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(mainAnswer1, deepDiveAnswer1, mainAnswer2));
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewReportRepository.save(report)).thenReturn(report);
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        llmResponse,
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        interviewReportService.generateReport(session.getId());

        BigDecimal expectedOverallScore = overallScoreCalculator.calculateWithPenalties(
                runningScore,
                session.getCompletionReason().name(),
                session.getAnsweredMainQuestionCount(),
                session.getMainQuestionCount(),
                List.of(branch1, branch2)
        ).overallScore();

        assertEquals(expectedOverallScore, report.getOverallScore());
    }

    @Test
    void generateReport_WhenSummaryPersistenceFails_CleansPartialArtifactsAndMarksFailed() {
        InterviewSession session = session();
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(7)
                .messageCount(6)
                .lastMessagePreview("previous")
                .lastMessageAt(LocalDateTime.parse("2026-03-27T12:00:00"))
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();

        String llmResponse = """
                {
                  "summary": "Summary that should be rolled back",
                  "strengths": ["Depth"],
                  "weaknesses": ["Speed"],
                  "improvementSuggestions": ["Improve structure"],
                  "skillAssessment": {
                    "Backend Development": {
                      "level": "advanced",
                      "evidence": "Evidence"
                    }
                  }
                }
                """;

        ChatMessage savedSummaryMessage = ChatMessage.builder()
                .id(501L)
                .externalId(UUID.randomUUID())
                .chatSessionId(chatSession.getId())
                .content("Summary that should be rolled back")
                .createdAt(LocalDateTime.parse("2026-03-27T12:01:00"))
                .build();

        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId())).thenReturn(List.of(
                InterviewQuestion.builder()
                        .id(51L)
                        .questionKind(InterviewQuestionKind.MAIN)
                        .questionContent("Tell me about your experience")
                        .sequenceNumber(1)
                        .build()
        ));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenReturn(savedSummaryMessage);
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        llmResponse,
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));
        when(interviewReportRepository.save(report))
                .thenReturn(report)
                .thenThrow(new RuntimeException("summary link save failed"))
                .thenReturn(report);

        interviewReportService.generateReport(session.getId());

        assertEquals(InterviewReportStatus.FAILED, report.getStatus());
        assertNull(report.getOverallScore());
        assertNull(report.getSummary());
        assertNull(report.getStrengths());
        assertNull(report.getWeaknesses());
        assertNull(report.getImprovementSuggestions());
        assertNull(report.getSkillAssessment());
        assertNull(report.getGeneratedAt());
        assertNull(report.getSummaryMessageId());
        assertEquals(7, chatSession.getNextMessageSequence());
        assertEquals(6, chatSession.getMessageCount());
        assertEquals("previous", chatSession.getLastMessagePreview());
        assertEquals(LocalDateTime.parse("2026-03-27T12:00:00"), chatSession.getLastMessageAt());
        verify(chatMessageRepository).delete(savedSummaryMessage);
    }

    @Test
    void generateReport_MethodItselfIsNotTransactional() throws Exception {
        Transactional transactional = InterviewReportService.class
                .getMethod("generateReport", Long.class)
                .getAnnotation(Transactional.class);

        assertFalse(transactional != null);
    }

    @Test
    void generateReport_PromptContainsOverallScoreCompletionReasonBranchFactsAndEvaluationSummary() {
        InterviewSession session = session();
        session.setCompletionReason(InterviewCompletionReason.USER_EARLY_END);
        session.setRunningScore(BigDecimal.valueOf(82.30).setScale(2));
        ChatSession chatSession = ChatSession.builder()
                .id(41L)
                .externalId(session.getChatSessionId())
                .userId(session.getUserId())
                .nextMessageSequence(1)
                .messageCount(0)
                .build();
        InterviewReport report = InterviewReport.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .sessionId(session.getId())
                .status(InterviewReportStatus.GENERATING)
                .build();
        InterviewQuestion mainQuestion = InterviewQuestion.builder()
                .id(51L)
                .questionKind(InterviewQuestionKind.MAIN)
                .sequenceNumber(1)
                .questionType("SCENARIO")
                .questionContent("How would you handle a production incident?")
                .build();
        InterviewAnswer mainAnswer = InterviewAnswer.builder()
                .questionId(51L)
                .evaluationScore(BigDecimal.valueOf(78))
                .answerContent("I would first stabilize the system and then inspect logs.")
                .evaluationDetails("""
                        {"schemaVersion":"interview-eval-v1","mode":"llm","fallbackUsed":false,
                        "rubric":{"answerRelevance":4,"specificity":4,"reasoning":4,"technicalJudgment":4,"communication":3},
                        "overallComment":"Good structure with reasonable trade-offs.","evidence":["stabilize first"],"risks":["rollback missing"]}
                        """)
                .build();

        String llmResponse = """
                {
                  "summary": "Summary",
                  "strengths": ["Depth"],
                  "weaknesses": ["Speed"],
                  "improvementSuggestions": ["Improve structure"],
                  "skillAssessment": {
                    "Backend Development": {
                      "level": "advanced",
                      "evidence": "Evidence"
                    }
                  }
                }
                """;

        when(interviewSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(interviewReportRepository.findBySessionId(session.getId())).thenReturn(Optional.of(report));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId()))
                .thenReturn(List.of(mainQuestion));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(mainAnswer));
        when(chatSessionRepository.findByExternalId(session.getChatSessionId())).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewReportRepository.save(report)).thenReturn(report);
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        llmResponse,
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        interviewReportService.generateReport(session.getId());

        org.mockito.ArgumentCaptor<com.josh.interviewj.llm.gateway.dto.AiInvocationInput> captor =
                org.mockito.ArgumentCaptor.forClass(com.josh.interviewj.llm.gateway.dto.AiInvocationInput.class);
        verify(aiOperationGateway).executeInvocation(any(), any(), captor.capture());
        String prompt = captor.getValue().userPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("USER_EARLY_END"));
        assertTrue(prompt.contains("Overall Score"));
        assertTrue(prompt.contains("branchScore"));
        assertTrue(prompt.contains("Good structure with reasonable trade-offs."));
    }

    private User user(String username) {
        return User.builder()
                .id(11L)
                .username(username)
                .email(username + "@example.com")
                .password("hashed")
                .build();
    }

    private InterviewSession session() {
        return InterviewSession.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .chatSessionId(UUID.randomUUID())
                .userId(11L)
                .status(InterviewStatus.COMPLETED)
                .completionReason(InterviewCompletionReason.USER_EARLY_END)
                .runningScore(BigDecimal.valueOf(82.3).setScale(2))
                .answeredMainQuestionCount(8)
                .mainQuestionCount(10)
                .contentLocale("zh-CN")
                .build();
    }

    private static void assertNotNull(Object obj) {
        org.junit.jupiter.api.Assertions.assertNotNull(obj);
    }
}
