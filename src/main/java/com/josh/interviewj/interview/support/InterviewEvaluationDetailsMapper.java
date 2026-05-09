package com.josh.interviewj.interview.support;

import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapper for normalizing evaluation details JSON to a consistent envelope format.
 *
 * <p>Handles backward compatibility for legacy evaluation details that only contained
 * an overallComment field. Converts them to the new envelope format with
 * mode, fallbackUsed, and rubric fields.</p>
 */
@Component
public class InterviewEvaluationDetailsMapper {

    private final ObjectMapper objectMapper;

    public InterviewEvaluationDetailsMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalize evaluation details JSON to the envelope format.
     *
     * <p>If the input is already in the new format, returns it as-is.
     * If the input is in the legacy format (only overallComment), converts it
     * to the envelope format with mode="legacy" and fallbackUsed=true.</p>
     *
     * @param evaluationDetails the raw evaluation details JSON
     * @return normalized map with envelope fields
     */
    public Map<String, Object> normalizeToEnvelope(String evaluationDetails) {
        if (evaluationDetails == null || evaluationDetails.isBlank()) {
            return toMap(InterviewEvaluationEnvelope.legacyCompat(null), "legacy_empty");
        }

        try {
            JsonNode node = objectMapper.readTree(evaluationDetails);

            if (node.has("schemaVersion") && node.has("rubric")) {
                return normalizeEnvelopeMap(objectMapper.convertValue(node, Map.class));
            }

            if (node.has("overallComment")) {
                return toMap(InterviewEvaluationEnvelope.legacyCompat(node.get("overallComment").asText()), "legacy_compat");
            }

            return toMap(InterviewEvaluationEnvelope.legacyCompat(evaluationDetails), "legacy_unknown");
        } catch (Exception e) {
            return toMap(InterviewEvaluationEnvelope.legacyCompat(null), "legacy_parse_error");
        }
    }

    private Map<String, Object> toMap(InterviewEvaluationEnvelope source, String modeOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", InterviewEvaluationEnvelope.SCHEMA_VERSION);
        payload.put("mode", modeOverride);
        payload.put("fallbackUsed", true);
        payload.put("rubric", createEmptyRubric());
        payload.put("overallComment", normalizeComment(source.overallComment()));
        payload.put("evidence", java.util.List.of());
        payload.put("risks", java.util.List.of());
        return payload;
    }

    private Map<String, Object> normalizeEnvelopeMap(Map<String, Object> envelope) {
        Map<String, Object> normalized = envelope == null ? new LinkedHashMap<>() : new LinkedHashMap<>(envelope);
        normalized.putIfAbsent("schemaVersion", InterviewEvaluationEnvelope.SCHEMA_VERSION);
        normalized.putIfAbsent("mode", "llm");
        normalized.putIfAbsent("fallbackUsed", false);
        normalized.put("overallComment", normalizeComment(normalized.get("overallComment")));
        normalized.put("evidence", normalizeListField(normalized.get("evidence")));
        normalized.put("risks", normalizeListField(normalized.get("risks")));
        normalized.put("rubric", normalizeRubric(normalized.get("rubric")));
        return normalized;
    }

    private Object normalizeRubric(Object rubricValue) {
        Map<String, Object> rubric = rubricValue instanceof Map<?, ?> rawMap
                ? new LinkedHashMap<>((Map<String, Object>) rawMap)
                : createEmptyRubric();
        rubric.putIfAbsent("answerRelevance", null);
        rubric.putIfAbsent("specificity", null);
        rubric.putIfAbsent("reasoning", null);
        rubric.putIfAbsent("technicalJudgment", null);
        rubric.putIfAbsent("communication", null);
        return rubric;
    }

    private Object normalizeListField(Object value) {
        return value == null ? java.util.List.of() : value;
    }

    private String normalizeComment(Object comment) {
        if (comment == null) {
            return "";
        }
        String text = String.valueOf(comment);
        return text == null ? "" : text;
    }

    private Map<String, Object> createEmptyRubric() {
        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("answerRelevance", null);
        rubric.put("specificity", null);
        rubric.put("reasoning", null);
        rubric.put("technicalJudgment", null);
        rubric.put("communication", null);
        return rubric;
    }
}
