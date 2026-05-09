package com.josh.interviewj.usage.dto.request;

import java.util.List;
import java.util.Map;

/**
 * Request to preview a draft template with variables.
 */
public record AdminPromptTemplatePreviewRequest(
        String systemTemplate,
        String userTemplate,
        List<VariableDeclaration> variables,
        Map<String, Object> renderVariables
) {
    public record VariableDeclaration(
            String name,
            boolean required
    ) {}
}