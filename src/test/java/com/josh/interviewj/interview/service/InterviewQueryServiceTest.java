package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.response.InterviewQuestionsResponse;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewEvaluationDetailsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewQueryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private InterviewQuestionRepository interviewQuestionRepository;

    @Mock
    private InterviewAnswerRepository interviewAnswerRepository;

    private InterviewQueryService interviewQueryService;

    @BeforeEach
    void setUp() {
        JsonMapper objectMapper = JsonMapper.builder().build();
        interviewQueryService = new InterviewQueryService(
                userRepository,
                interviewSessionRepository,
                interviewQuestionRepository,
                interviewAnswerRepository,
                new InterviewEvaluationDetailsMapper(objectMapper)
        );
    }

    @Test
    void getQuestions_ReturnsMainAndFollowUpNodesInSequenceOrder() {
        UUID interviewId = UUID.randomUUID();
        UUID mainQuestionId = UUID.randomUUID();
        UUID followUpQuestionId = UUID.randomUUID();
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
                .chatSessionId(UUID.randomUUID())
                .status(InterviewStatus.IN_PROGRESS)
                .build();
        InterviewQuestion mainQuestion = InterviewQuestion.builder()
                .id(31L)
                .externalId(mainQuestionId)
                .sessionId(21L)
                .questionKind(InterviewQuestionKind.MAIN)
                .questionType("EXPERIENCE")
                .questionContent("Describe a backend migration you led.")
                .difficulty(3)
                .estimatedMinutes(3)
                .sequenceNumber(1)
                .branchDepth(0)
                .build();
        InterviewQuestion followUpQuestion = InterviewQuestion.builder()
                .id(32L)
                .externalId(followUpQuestionId)
                .sessionId(21L)
                .questionKind(InterviewQuestionKind.FOLLOW_UP)
                .followUpIntent(InterviewFollowUpIntent.DEEP_DIVE)
                .parentQuestionId(31L)
                .questionType("EXPERIENCE")
                .questionContent("How did you keep zero downtime?")
                .difficulty(4)
                .estimatedMinutes(2)
                .sequenceNumber(2)
                .branchDepth(1)
                .build();
        InterviewAnswer answer = InterviewAnswer.builder()
                .id(41L)
                .externalId(UUID.randomUUID())
                .sessionId(21L)
                .questionId(31L)
                .answerContent("I split traffic by tenant and verified canaries first.")
                .durationSeconds(128)
                .evaluationScore(BigDecimal.valueOf(84.0))
                .evaluationDetails("{\"overallComment\":\"Structured answer\"}")
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, 11L))
                .thenReturn(Optional.of(session));
        when(interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(21L))
                .thenReturn(List.of(mainQuestion, followUpQuestion));
        when(interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(21L)).thenReturn(List.of(answer));

        InterviewQuestionsResponse response = interviewQueryService.getQuestions("josh", interviewId);

        assertEquals(2, response.questions().size());
        assertEquals(mainQuestionId, response.questions().getFirst().questionId());
        assertEquals("MAIN", response.questions().getFirst().questionKind());
        assertEquals(answer.getExternalId(), response.questions().getFirst().answer().answerId());
        assertEquals(followUpQuestionId, response.questions().get(1).questionId());
        assertEquals("FOLLOW_UP", response.questions().get(1).questionKind());
        assertEquals("DEEP_DIVE", response.questions().get(1).followUpIntent());
        assertEquals(mainQuestionId, response.questions().get(1).parentQuestionId());
    }

    @Test
    void getQuestions_DeletedInterviewThrowsNotFound() {
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

        assertThrows(BusinessException.class, () -> interviewQueryService.getQuestions("josh", interviewId));
    }
}
