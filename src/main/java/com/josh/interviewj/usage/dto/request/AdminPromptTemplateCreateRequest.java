package com.josh.interviewj.usage.dto.request;

import java.util.List;

/**
 * Request to create a new prompt template identity with initial revision.
 */
public record AdminPromptTemplateCreateRequest(
        String templateKey,
        String domain,
        String purpose,
        String invocationKind,
        String description,
        Boolean enabled,
        String systemTemplate,
        String userTemplate,
        List<VariableDeclaration> variables,
        String changeNote,
        String createdBy
) {
    public record VariableDeclaration(
            String name,
            boolean required
    ) {}
}