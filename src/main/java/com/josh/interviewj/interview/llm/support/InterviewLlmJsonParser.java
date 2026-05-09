package com.josh.interviewj.interview.llm.support;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload;
import com.josh.interviewj.interview.llm.dto.InterviewGeneratedQuestionPayload;
import com.josh.interviewj.interview.llm.dto.InterviewReportPayload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for interview LLM JSON responses with strict validation.
 */
@Component
public class InterviewLlmJsonParser {

    private final ObjectMapper objectMapper;

    public InterviewLlmJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse questions from LLM response.
     *
     * @param json JSON string
     * @return list of question payloads
     * @throws BusinessException if parsing or validation fails
     */
    public List<InterviewGeneratedQuestionPayload> parseQuestions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode questionsNode = root.path("questions");

            if (!questionsNode.isArray()) {
                throw new BusinessException("LLM_003", "Missing or invalid 'questions' array in response");
            }

            List<InterviewGeneratedQuestionPayload> questions = new ArrayList<>();
            int index = 0;
            java.util.Set<Integer> sequenceNumbers = new java.util.HashSet<>();

            for (JsonNode questionNode : questionsNode) {
                index++;
                InterviewGeneratedQuestionPayload payload = parseQuestion(questionNode, index);
                if (!payload.isValid()) {
                    throw new BusinessException("LLM_003",
                            "Invalid question at index " + index + ": missing required fields or invalid values");
                }
                if (!sequenceNumbers.add(payload.sequenceNumber())) {
                    throw new BusinessException("LLM_003", "Duplicate sequenceNumber in generated questions: " + payload.sequenceNumber());
                }
                questions.add(payload);
            }

            if (questions.isEmpty()) {
                throw new BusinessException("LLM_003", "No questions generated in response");
            }

            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("LLM_003", "Failed to parse questions: " + e.getMessage(), e);
        }
    }

    private InterviewGeneratedQuestionPayload parseQuestion(JsonNode node, int index) {
        Integer sequenceNumber = extractRequiredInteger(node, "sequenceNumber", "question[" + index + "]");
        String questionContent = extractRequiredString(node, "questionContent", "question[" + index + "]");
        String focusHint = extractOptionalString(node, "focusHint");

        return new InterviewGeneratedQuestionPayload(
                sequenceNumber,
                questionContent,
                focusHint
        );
    }

    /**
     * Parse evaluation rubric from LLM response.
     *
     * @param json JSON string
     * @return evaluation rubric payload
     * @throws BusinessException if parsing or validation fails
     */
    public InterviewEvaluationRubricPayload parseEvaluationRubric(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            Integer answerRelevance = extractRequiredInteger(root, "answerRelevance", "evaluation");
            Integer specificity = extractRequiredInteger(root, "specificity", "evaluation");
            Integer reasoning = extractRequiredInteger(root, "reasoning", "evaluation");
            Integer technicalJudgment = extractRequiredInteger(root, "technicalJudgment", "evaluation");
            Integer communication = extractRequiredInteger(root, "communication", "evaluation");
            String overallComment = extractRequiredString(root, "overallComment", "evaluation");
            List<String> evidence = extractRequiredStringArray(root, "evidence", "evaluation", true);
            List<String> risks = extractRequiredStringArray(root, "risks", "evaluation", true);

            InterviewEvaluationRubricPayload payload = new InterviewEvaluationRubricPayload(
                    answerRelevance, specificity, reasoning, technicalJudgment, communication, overallComment, evidence, risks
            );

            if (!payload.isValid()) {
                throw new BusinessException("LLM_003", "Invalid evaluation rubric: scores must be 0-5 and comment is required");
            }

            return payload;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("LLM_003", "Failed to parse evaluation rubric: " + e.getMessage(), e);
        }
    }

    /**
     * Parse report from LLM response.
     *
     * @param json JSON string
     * @return report payload
     * @throws BusinessException if parsing or validation fails
     */
    public InterviewReportPayload parseReport(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String summary = extractRequiredString(root, "summary", "report");
            List<String> strengths = extractRequiredStringArray(root, "strengths", "report", false);
            List<String> weaknesses = extractRequiredStringArray(root, "weaknesses", "report", false);
            List<String> improvementSuggestions = extractOptionalStringArray(root, "improvementSuggestions");
            Map<String, InterviewReportPayload.SkillAssessment> skillAssessment = parseSkillAssessment(root.path("skillAssessment"));

            InterviewReportPayload payload = new InterviewReportPayload(
                    summary, strengths, weaknesses, improvementSuggestions, skillAssessment
            );

            if (!payload.isValid()) {
                throw new BusinessException("LLM_003", "Invalid report: summary, strengths, and weaknesses are required");
            }

            return payload;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("LLM_003", "Failed to parse report: " + e.getMessage(), e);
        }
    }

    private String extractRequiredString(JsonNode node, String field, String context) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            throw new BusinessException("LLM_003", "Missing required field '" + field + "' in " + context);
        }
        String value = fieldNode.asText();
        if (value == null || value.isBlank()) {
            throw new BusinessException("LLM_003", "Required field '" + field + "' is empty in " + context);
        }
        return value;
    }

    private String extractOptionalString(JsonNode node, String field) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return null;
        }
        return fieldNode.asText();
    }

    private Integer extractRequiredInteger(JsonNode node, String field, String context) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            throw new BusinessException("LLM_003", "Missing required field '" + field + "' in " + context);
        }
        if (!fieldNode.isNumber()) {
            throw new BusinessException("LLM_003", "Field '" + field + "' must be an integer in " + context);
        }
        return fieldNode.asInt();
    }

    public String parseFollowUpQuestionContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return extractRequiredString(root, "questionContent", "followUp");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("LLM_003", "Failed to parse follow-up question: " + e.getMessage(), e);
        }
    }

    private List<String> extractRequiredStringArray(JsonNode node, String field, String context, boolean allowEmpty) {
        JsonNode arrayNode = node.path(field);
        if (!arrayNode.isArray()) {
            throw new BusinessException("LLM_003", "Missing or invalid array '" + field + "' in " + context);
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (!item.isTextual()) {
                throw new BusinessException("LLM_003", "Array '" + field + "' must contain only strings in " + context);
            }
            String value = item.asText();
            if (value != null) {
                values.add(value);
            }
        }

        if (!allowEmpty && values.isEmpty()) {
            throw new BusinessException("LLM_003", "Array '" + field + "' must not be empty in " + context);
        }

        return values;
    }

    private List<String> extractOptionalStringArray(JsonNode node, String field) {
        JsonNode arrayNode = node.path(field);
        if (!arrayNode.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            String value = item.asText();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }

        return values;
    }

    private Map<String, InterviewReportPayload.SkillAssessment> parseSkillAssessment(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }

        Map<String, InterviewReportPayload.SkillAssessment> result = new HashMap<>();
        for (var entry : node.properties()) {
            String skillName = entry.getKey();
            JsonNode assessmentNode = entry.getValue();

            String level = assessmentNode.path("level").asText();
            String evidence = assessmentNode.path("evidence").asText();

            if (level != null && !level.isBlank() && evidence != null && !evidence.isBlank()) {
                result.put(skillName, new InterviewReportPayload.SkillAssessment(level, evidence));
            }
        }

        return result;
    }
}
