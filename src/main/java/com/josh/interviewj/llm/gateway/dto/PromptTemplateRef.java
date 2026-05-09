package com.josh.interviewj.llm.gateway.dto;

import java.util.Map;

/**
 * Reference to a prompt template with variables for runtime rendering.
 * Used when business service wants to use database-managed prompt templates.
 */
public record PromptTemplateRef(
        String templateKey,
        Map<String, Object> variables
) {
    public PromptTemplateRef {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}