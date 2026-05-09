package com.josh.interviewj.interview.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.config.InterviewQuestionGenerationProperties;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.support.InterviewDeterministicQuestionBuilder;
import com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewQuestionGenerationServiceTest {

    @Mock
    private AiOperationGateway aiOperationGateway;

    private InterviewQuestionGenerationService service;

    @BeforeEach
    void setUp() {
        InterviewQuestionBlueprintFactory blueprintFactory = new InterviewQuestionBlueprintFactory();
        InterviewDeterministicQuestionBuilder deterministicBuilder =
                new InterviewDeterministicQuestionBuilder(blueprintFactory);
        InterviewLlmJsonParser jsonParser = new InterviewLlmJsonParser(JsonMapper.builder().build());
        InterviewQuestionGenerationProperties properties = new InterviewQuestionGenerationProperties();
        properties.setFallbackEnabled(true); // Default behavior for existing tests
        service = new InterviewQuestionGenerationService(
                aiOperationGateway,
                blueprintFactory,
                deterministicBuilder,
                jsonParser,
                properties
        );
        lenient().when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                9L,
                "INTERVIEW_SESSION",
                "session-1",
                "interview_question_generation",
                List.of("INTERVIEW_CREDITS"),
                Map.of()
        ));
    }

    @Test
    void generateMainQuestions_WithoutResume_StillUsesLlmAndAppliesBlueprintShape() {
        InterviewSession session = InterviewSession.builder()
                .id(11L)
                .externalId(java.util.UUID.randomUUID())
                .userId(9L)
                .jobTitle("Backend Engineer")
                .jobDescription("Build reliable Java services with distributed systems concerns.")
                .difficultyLevel("MID")
                .build();

        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse("""
                {
                  "questions": [
                    {"sequenceNumber": 1, "questionContent": "Q1"},
                    {"sequenceNumber": 2, "questionContent": "Q2"},
                    {"sequenceNumber": 3, "questionContent": "Q3"},
                    {"sequenceNumber": 4, "questionContent": "Q4"},
                    {"sequenceNumber": 5, "questionContent": "Q5"},
                    {"sequenceNumber": 6, "questionContent": "Q6"},
                    {"sequenceNumber": 7, "questionContent": "Q7"},
                    {"sequenceNumber": 8, "questionContent": "Q8"},
                    {"sequenceNumber": 9, "questionContent": "Q9"},
                    {"sequenceNumber": 10, "questionContent": "Q10"}
                  ]
                }
                """, "mock", "mock-model", new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L))));

        List<InterviewQuestion> questions = service.generateMainQuestions(session, null, "zh-CN");

        assertEquals(10, questions.size());
        assertEquals(1, questions.getFirst().getSequenceNumber());
        assertEquals("EXPERIENCE", questions.getFirst().getQuestionType());
        assertEquals(8, questions.getFirst().getEstimatedMinutes());
        assertEquals(10, questions.getLast().getSequenceNumber());
        assertEquals("SKILL", questions.getLast().getQuestionType());
        verify(aiOperationGateway).executeInvocation(any(), any(), any());
    }

    @Test
    void generateMainQuestions_WithResume_LlmFailure_FailsClosed() {
        InterviewSession session = InterviewSession.builder()
                .id(12L)
                .externalId(java.util.UUID.randomUUID())
                .userId(9L)
                .jobTitle("Backend Engineer")
                .jobDescription("Build reliable Java services.")
                .difficultyLevel("MID")
                .build();
        Resume resume = Resume.builder()
                .id(21L)
                .status(ResumeStatus.PARSED)
                .parsedContent("10 years of backend Java experience.")
                .build();

        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_001", "provider failed"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.generateMainQuestions(session, resume, "zh-CN")
        );

        assertEquals("INTERVIEW_009", exception.getErrorCode());
    }

    @Test
    void generateMainQuestions_WithoutResume_FallbackRecordsNonChargeableUsage() {
        InterviewSession session = InterviewSession.builder()
                .id(14L)
                .externalId(java.util.UUID.randomUUID())
                .userId(9L)
                .jobTitle("Backend Engineer")
                .jobDescription("Build reliable Java services.")
                .difficultyLevel("MID")
                .build();
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(
                new LlmResponse(
                        "{\"questions\":[]}",
                        "mock",
                        "mock-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )
        ));

        List<InterviewQuestion> questions = service.generateMainQuestions(session, null, "zh-CN");

        org.junit.jupiter.api.Assertions.assertFalse(questions.isEmpty());
        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void generateMainQuestions_PassesBlueprintAndJobDescriptionIntoPrompt() {
        InterviewSession session = InterviewSession.builder()
                .id(13L)
                .externalId(java.util.UUID.randomUUID())
                .userId(9L)
                .jobTitle("Backend Engineer")
                .jobDescription("Need strong API design, transaction handling, and distributed tracing.")
                .difficultyLevel("JUNIOR")
                .build();

        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse("""
                {
                  "questions": [
                    {"sequenceNumber": 1, "questionContent": "Q1"},
                    {"sequenceNumber": 2, "questionContent": "Q2"},
                    {"sequenceNumber": 3, "questionContent": "Q3"},
                    {"sequenceNumber": 4, "questionContent": "Q4"},
                    {"sequenceNumber": 5, "questionContent": "Q5"},
                    {"sequenceNumber": 6, "questionContent": "Q6"},
                    {"sequenceNumber": 7, "questionContent": "Q7"},
                    {"sequenceNumber": 8, "questionContent": "Q8"}
                  ]
                }
                """, "mock", "mock-model", new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L))));

        service.generateMainQuestions(session, null, "zh-CN");

        ArgumentCaptor<AiInvocationInput> captor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway).executeInvocation(any(), any(), captor.capture());
        assertNotNull(captor.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().userPrompt().contains(session.getJobDescription()));
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().userPrompt().contains("sequenceNumber"));
    }
}
