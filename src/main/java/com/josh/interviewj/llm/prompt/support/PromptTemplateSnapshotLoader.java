package com.josh.interviewj.llm.prompt.support;

import com.josh.interviewj.llm.prompt.dto.PromptTemplateSnapshot;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplate;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplateRevision;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRepository;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Loads prompt template snapshot from database.
 * Handles enabled check and active revision lookup.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PromptTemplateSnapshotLoader {

    private final LlmPromptTemplateRepository templateRepository;
    private final LlmPromptTemplateRevisionRepository revisionRepository;

    /**
     * Load snapshot for a template key.
     * Returns empty if template not found, disabled, or no active revision.
     */
    public Optional<PromptTemplateSnapshot> load(String templateKey) {
        try {
            Optional<LlmPromptTemplate> templateOpt = templateRepository.findByTemplateKey(templateKey);
            if (templateOpt.isEmpty()) {
                log.debug("Template not found: {}", templateKey);
                return Optional.empty();
            }

            LlmPromptTemplate template = templateOpt.get();
            if (!template.getEnabled()) {
                log.debug("Template disabled: {}", templateKey);
                return Optional.empty();
            }

            if (template.getActiveRevisionId() == null) {
                log.debug("Template has no active revision: {}", templateKey);
                return Optional.empty();
            }

            Optional<LlmPromptTemplateRevision> revisionOpt = revisionRepository.findById(template.getActiveRevisionId());
            if (revisionOpt.isEmpty()) {
                log.warn("Active revision not found for template: {}, revisionId: {}", templateKey, template.getActiveRevisionId());
                return Optional.empty();
            }

            LlmPromptTemplateRevision revision = revisionOpt.get();
            List<PromptTemplateSnapshot.VariableDeclaration> variables = parseVariables(revision.getVariables());

            return Optional.of(new PromptTemplateSnapshot(
                    template.getTemplateKey(),
                    template.getId(),
                    revision.getRevisionNo(),
                    revision.getSystemTemplate(),
                    revision.getUserTemplate(),
                    variables,
                    template.getEnabled()
            ));
        } catch (Exception e) {
            log.error("Failed to load template snapshot: {}", templateKey, e);
            return Optional.empty();
        }
    }

    /**
     * Parse variables JSON string into declaration list.
     * JSON format: [{"name":"jobTitle","required":true}]
     */
    private List<PromptTemplateSnapshot.VariableDeclaration> parseVariables(String variablesJson) {
        if (variablesJson == null || variablesJson.isBlank()) {
            return List.of();
        }

        // Simple manual parsing for minimal format
        // [{"name":"jobTitle","required":true},{"name":"locale","required":false}]
        try {
            List<PromptTemplateSnapshot.VariableDeclaration> result = new java.util.ArrayList<>();
            String json = variablesJson.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                return List.of();
            }

            // Remove outer brackets and parse objects
            String content = json.substring(1, json.length() - 1);
            if (content.isBlank()) {
                return List.of();
            }

            // Split by objects (simple approach - may need refinement for complex cases)
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
            java.util.regex.Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String obj = matcher.group(1);
                String name = extractValue(obj, "name");
                boolean required = Boolean.parseBoolean(extractValue(obj, "required"));
                if (name != null) {
                    result.add(new PromptTemplateSnapshot.VariableDeclaration(name, required));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse variables JSON: {}", variablesJson, e);
            return List.of();
        }
    }

    private String extractValue(String objContent, String key) {
        // "name":"jobTitle" or "name": "jobTitle"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(objContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Also try for boolean values: "required":true
        pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        matcher = pattern.matcher(objContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}