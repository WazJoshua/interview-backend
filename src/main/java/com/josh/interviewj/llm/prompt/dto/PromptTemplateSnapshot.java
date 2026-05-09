package com.josh.interviewj.llm.prompt.dto;

import java.util.List;

/**
 * Snapshot of a prompt template loaded from database.
 * Contains the active revision content and variable declarations.
 */
public record PromptTemplateSnapshot(
        String templateKey,
        Long templateId,
        Integer revisionNo,
        String systemTemplate,
        String userTemplate,
        List<VariableDeclaration> variables,
        boolean enabled
) {
    /**
     * Variable declaration from revision.
     */
    public record VariableDeclaration(
            String name,
            boolean required
    ) {}
}