package com.josh.interviewj.interview.llm.support;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.llm.dto.InterviewGeneratedQuestionPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewLlmJsonParserTest {

    private ObjectMapper objectMapper;
    private InterviewLlmJsonParser parser;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        parser = new InterviewLlmJsonParser(objectMapper);
    }

    @Test
    void parseQuestions_ValidJson_ReturnsQuestions() {
        String json = """
                {
                  "questions": [
                    {
                      "sequenceNumber": 1,
                      "questionContent": "Describe your experience with Java",
                      "focusHint": "Focus on backend development"
                    },
                    {
                      "sequenceNumber": 2,
                      "questionContent": "How would you handle a memory leak?"
                    }
                  ]
                }
                """;

        List<InterviewGeneratedQuestionPayload> questions = parser.parseQuestions(json);

        assertEquals(2, questions.size());
        assertEquals(1, questions.get(0).sequenceNumber());
        assertEquals("Describe your experience with Java", questions.get(0).questionContent());
        assertEquals("Focus on backend development", questions.get(0).focusHint());

        assertTrue(questions.get(0).isValid());
        assertTrue(questions.get(1).isValid());
    }

    @Test
    void parseQuestions_MissingQuestionsArray_ThrowsException() {
        String json = """
                {
                  "other": []
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseQuestions(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseQuestions_EmptyQuestionsArray_ThrowsException() {
        String json = """
                {
                  "questions": []
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseQuestions(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseQuestions_MissingRequiredField_ThrowsException() {
        String json = """
                {
                  "questions": [
                    {
                      "questionContent": "Test question"
                    }
                  ]
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseQuestions(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseQuestions_InvalidQuestionContent_ThrowsException() {
        String json = """
                {
                  "questions": [
                    {
                      "sequenceNumber": 1,
                      "questionContent": ""
                    }
                  ]
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseQuestions(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseEvaluationRubric_ValidJson_ReturnsRubric() {
        String json = """
                {
                  "answerRelevance": 4,
                  "specificity": 3,
                  "reasoning": 5,
                  "technicalJudgment": 4,
                  "communication": 3,
                  "overallComment": "Good answer with room for improvement",
                  "evidence": ["Used a concrete example"],
                  "risks": ["Did not quantify impact"]
                }
                """;

        var rubric = parser.parseEvaluationRubric(json);

        assertEquals(4, rubric.answerRelevance());
        assertEquals(3, rubric.specificity());
        assertEquals(5, rubric.reasoning());
        assertEquals(4, rubric.technicalJudgment());
        assertEquals(3, rubric.communication());
        assertEquals("Good answer with room for improvement", rubric.overallComment());
        assertEquals(1, rubric.evidence().size());
        assertEquals(1, rubric.risks().size());
        assertTrue(rubric.isValid());
    }

    @Test
    void parseEvaluationRubric_MissingField_ThrowsException() {
        String json = """
                {
                  "answerRelevance": 4,
                  "specificity": 3
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseEvaluationRubric(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseEvaluationRubric_InvalidScore_ThrowsException() {
        String json = """
                {
                  "answerRelevance": 6,
                  "specificity": 3,
                  "reasoning": 5,
                  "technicalJudgment": 4,
                  "communication": 3,
                  "overallComment": "Test",
                  "evidence": [],
                  "risks": []
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseEvaluationRubric(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseReport_ValidJson_ReturnsReport() {
        String json = """
                {
                  "summary": "Strong candidate with good technical skills",
                  "strengths": ["Problem solving", "Communication"],
                  "weaknesses": ["System design"],
                  "improvementSuggestions": ["Study distributed systems"],
                  "skillAssessment": {
                    "Java": {
                      "level": "advanced",
                      "evidence": "Demonstrated deep knowledge"
                    }
                  }
                }
                """;

        var report = parser.parseReport(json);

        assertEquals("Strong candidate with good technical skills", report.summary());
        assertEquals(2, report.strengths().size());
        assertEquals(1, report.weaknesses().size());
        assertEquals(1, report.improvementSuggestions().size());
        assertEquals(1, report.skillAssessment().size());
        assertTrue(report.isValid());
    }

    @Test
    void parseReport_MissingRequiredField_ThrowsException() {
        String json = """
                {
                  "summary": "Test",
                  "strengths": []
                }
                """;

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseReport(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }

    @Test
    void parseReport_InvalidJson_ThrowsException() {
        String json = "not valid json";

        BusinessException ex = assertThrows(BusinessException.class, () -> parser.parseReport(json));
        assertEquals("LLM_003", ex.getErrorCode());
    }
}
