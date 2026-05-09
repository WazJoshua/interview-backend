package com.josh.interviewj.interview.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewEvaluationDetailsMapperTest {

    private InterviewEvaluationDetailsMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new InterviewEvaluationDetailsMapper(JsonMapper.builder().build());
    }

    @Test
    void normalizeToEnvelope_NewFormat_ReturnsAsIs() {
        String newFormat = """
                {
                  "schemaVersion": "interview-eval-v1",
                  "mode": "llm",
                  "fallbackUsed": false,
                  "rubric": {
                    "answerRelevance": 4,
                    "specificity": 5,
                    "reasoning": 4,
                    "technicalJudgment": 4,
                    "communication": 5
                  },
                  "communication": 5,
                  "overallComment": "Excellent answer.",
                  "evidence": ["Concrete example"],
                  "risks": ["Missing rollback detail"]
                }
                """;

        Map<String, Object> envelope = mapper.normalizeToEnvelope(newFormat);

        assertEquals("interview-eval-v1", envelope.get("schemaVersion"));
        assertEquals("llm", envelope.get("mode"));
        assertFalse((Boolean) envelope.get("fallbackUsed"));
        Map<String, Object> rubric = (Map<String, Object>) envelope.get("rubric");
        assertEquals(4, rubric.get("answerRelevance"));
        assertEquals(5, rubric.get("specificity"));
        assertEquals("Excellent answer.", envelope.get("overallComment"));
    }

    @Test
    void normalizeToEnvelope_PartialNewFormat_FillsMissingEnvelopeFields() {
        String partialNewFormat = """
                {
                  "schemaVersion": "interview-eval-v1",
                  "rubric": {
                    "answerRelevance": 4
                  }
                }
                """;

        Map<String, Object> envelope = mapper.normalizeToEnvelope(partialNewFormat);

        assertEquals("interview-eval-v1", envelope.get("schemaVersion"));
        assertEquals("llm", envelope.get("mode"));
        assertEquals(false, envelope.get("fallbackUsed"));
        assertEquals("", envelope.get("overallComment"));
        assertEquals(java.util.List.of(), envelope.get("evidence"));
        assertEquals(java.util.List.of(), envelope.get("risks"));
        Map<String, Object> rubric = (Map<String, Object>) envelope.get("rubric");
        assertEquals(4, rubric.get("answerRelevance"));
        assertTrue(rubric.containsKey("specificity"));
        assertTrue(rubric.containsKey("reasoning"));
        assertTrue(rubric.containsKey("technicalJudgment"));
        assertTrue(rubric.containsKey("communication"));
    }

    @Test
    void normalizeToEnvelope_HeuristicFallback_ReturnsAsIs() {
        String heuristicFormat = """
                {
                  "schemaVersion": "interview-eval-v1",
                  "mode": "heuristic_fallback",
                  "fallbackUsed": true,
                  "rubric": {
                    "answerRelevance": null,
                    "specificity": null,
                    "reasoning": null,
                    "technicalJudgment": null,
                    "communication": null
                  },
                  "overallComment": "Answer needs more detail.",
                  "evidence": [],
                  "risks": []
                }
                """;

        Map<String, Object> envelope = mapper.normalizeToEnvelope(heuristicFormat);

        assertEquals("heuristic_fallback", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
        Map<String, Object> rubric = (Map<String, Object>) envelope.get("rubric");
        assertNull(rubric.get("answerRelevance"));
        assertNull(rubric.get("specificity"));
        assertEquals("Answer needs more detail.", envelope.get("overallComment"));
    }

    @Test
    void normalizeToEnvelope_LegacyFormat_ConvertsToEnvelope() {
        String legacyFormat = """
                {"overallComment":"Good effort."}
                """;

        Map<String, Object> envelope = mapper.normalizeToEnvelope(legacyFormat);

        assertEquals("interview-eval-v1", envelope.get("schemaVersion"));
        assertEquals("legacy_compat", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
        Map<String, Object> rubric = (Map<String, Object>) envelope.get("rubric");
        assertNull(rubric.get("answerRelevance"));
        assertNull(rubric.get("specificity"));
        assertNull(rubric.get("reasoning"));
        assertNull(rubric.get("technicalJudgment"));
        assertNull(rubric.get("communication"));
        assertEquals("Good effort.", envelope.get("overallComment"));
    }

    @Test
    void normalizeToEnvelope_NullInput_ReturnsEmptyEnvelope() {
        Map<String, Object> envelope = mapper.normalizeToEnvelope(null);

        assertEquals("interview-eval-v1", envelope.get("schemaVersion"));
        assertEquals("legacy_empty", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
        assertEquals("", envelope.get("overallComment"));
    }

    @Test
    void normalizeToEnvelope_EmptyInput_ReturnsEmptyEnvelope() {
        Map<String, Object> envelope = mapper.normalizeToEnvelope("");

        assertEquals("legacy_empty", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
    }

    @Test
    void normalizeToEnvelope_InvalidJson_ReturnsErrorEnvelope() {
        Map<String, Object> envelope = mapper.normalizeToEnvelope("not valid json");

        assertEquals("legacy_parse_error", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
    }

    @Test
    void normalizeToEnvelope_UnknownFormat_WrapsInEnvelope() {
        String unknownFormat = """
                {"someField":"someValue"}
                """;

        Map<String, Object> envelope = mapper.normalizeToEnvelope(unknownFormat);

        assertEquals("legacy_unknown", envelope.get("mode"));
        assertTrue((Boolean) envelope.get("fallbackUsed"));
        assertNotNull(envelope.get("overallComment"));
    }
}
