package com.josh.interviewj.resume.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.common.util.RegexPatterns;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StructuredExtractionService {

    private static final String PURPOSE_PARSE = "parse";
    private static final List<String> REQUIRED_TOP_LEVEL_KEYS = List.of(
            "personalInfo",
            "education",
            "workExperience",
            "skills",
            "projects"
    );

    private static final String SYSTEM_PROMPT = "You are a resume parsing assistant. " +
            "Return ONLY a JSON object with top-level keys: " +
            "personalInfo, education, workExperience, skills, projects. " +
            "Do not include markdown, code fences, or extra text.";

    private final ObjectMapper objectMapper;
    private final AiOperationGateway aiOperationGateway;

    /**
     * Extract structured resume content from raw text.
     *
     * <p>Pipeline: regex rule hints -> LLM structuring -> minimal schema gate (top-level keys).</p>
     *
     * @param rawText extracted raw text
     * @return structured JSON string
     */
    public String extract(String rawText) {
        return extractWithUsage(rawText).parsedContent();
    }

    public StructuredExtractionResult extractWithUsage(String rawText) {
        String businessOperationId = "structured-extract-" + UUID.randomUUID();
        return extractWithUsage(
                rawText,
                new BusinessOperationContext(
                        businessOperationId,
                        0L,
                        "RESUME",
                        businessOperationId,
                        PURPOSE_PARSE,
                        List.of("RESUME_CREDITS"),
                        Map.of()
                ),
                new AiInvocationContext(
                        businessOperationId + ":chat",
                        PURPOSE_PARSE,
                        UsageFamily.CHAT,
                        "RESUME_CREDITS",
                        false,
                        Map.of()
                )
        );
    }

    public StructuredExtractionResult extractWithUsage(
            String rawText,
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext
    ) {
        // 1) Generate deterministic hints for the LLM.
        ObjectNode ruleHints = buildRuleHints(rawText);
        String userPrompt = buildUserPrompt(rawText, ruleHints);

        // 2) Ask the LLM for structured JSON.
        LlmResponse llmResponse = aiOperationGateway.executeInvocation(
                operationContext,
                invocationContext,
                AiInvocationInput.chat(SYSTEM_PROMPT, userPrompt, null,
                        new PromptTemplateRef("resume_parse", Map.of(
                                "rawText", rawText == null ? "" : rawText,
                                "ruleHints", ruleHints.toString()
                        )))
        ).llmResponse();

        try {
            String json = llmResponse.content();
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.isObject()) {
                throw new BusinessException("RESUME_003", "Resume parse failed: parsed content must be a JSON object");
            }

            // 3) Enforce minimal contract so downstream rendering is stable.
            ObjectNode root = (ObjectNode) parsed;
            ensureRequiredKeys(root);
            return new StructuredExtractionResult(objectMapper.writeValueAsString(root), llmResponse);
        } catch (BusinessException ex) {
            throw new StructuredExtractionException(ex.getErrorCode(), ex.getMessage(), llmResponse, ex);
        } catch (Exception e) {
            throw new StructuredExtractionException("RESUME_003", "Resume parse failed", llmResponse, e);
        }
    }

    /**
     * Build rule hints (emails/phones/dates) from raw text.
     *
     * @param rawText raw text
     * @return JSON object containing rule hint arrays
     */
    private ObjectNode buildRuleHints(String rawText) {
        ObjectNode hints = objectMapper.createObjectNode();

        List<String> emails = RegexPatterns.extractEmails(rawText);
        List<String> phones = RegexPatterns.extractPhones(rawText);
        List<String> dates = RegexPatterns.extractDates(rawText);

        ArrayNode emailsNode = hints.putArray("emails");
        emails.forEach(emailsNode::add);

        ArrayNode phonesNode = hints.putArray("phones");
        phones.forEach(phonesNode::add);

        ArrayNode datesNode = hints.putArray("dates");
        dates.forEach(datesNode::add);

        return hints;
    }

    /**
     * Compose the user prompt with raw text and rule hints.
     *
     * @param rawText raw resume text
     * @param ruleHints regex-based hints
     * @return user prompt text
     */
    private String buildUserPrompt(String rawText, ObjectNode ruleHints) {
        String safeText = rawText == null ? "" : rawText;

        return "Raw resume text:\n" +
                safeText +
                "\n\nRule hints (emails/phones/dates):\n" +
                ruleHints.toString() +
                "\n\nPlease produce the structured JSON.";
    }

    /**
     * Ensure required top-level keys exist with conservative default shapes.
     *
     * @param root parsed content root object
     */
    private void ensureRequiredKeys(ObjectNode root) {
        // Default shapes are conservative for rendering/usage.
        Map<String, JsonNode> defaults = Map.of(
                "personalInfo", objectMapper.createObjectNode(),
                "education", objectMapper.createArrayNode(),
                "workExperience", objectMapper.createArrayNode(),
                "skills", objectMapper.createArrayNode(),
                "projects", objectMapper.createArrayNode()
        );

        for (String key : REQUIRED_TOP_LEVEL_KEYS) {
            JsonNode current = root.get(key);
            if (current == null || current.isNull()) {
                root.set(key, defaults.get(key));
                continue;
            }

            // If LLM outputs wrong type, keep content but log; downstream consumers should treat as low confidence.
            if (key.equals("personalInfo") && !current.isObject()) {
                log.warn("personalInfo is not an object, key overwritten with empty object");
                root.set(key, defaults.get(key));
            }
        }
    }

    public static class StructuredExtractionException extends BusinessException {

        private final LlmResponse llmResponse;

        public StructuredExtractionException(String errorCode, String message, LlmResponse llmResponse, Throwable cause) {
            super(errorCode, message, cause);
            this.llmResponse = llmResponse;
        }

        public LlmResponse llmResponse() {
            return llmResponse;
        }
    }
}
