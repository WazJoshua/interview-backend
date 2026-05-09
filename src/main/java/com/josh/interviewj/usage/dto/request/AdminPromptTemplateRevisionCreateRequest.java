package com.josh.interviewj.usage.dto.request;

import java.util.List;

/**
 * Request to create a new revision for an existing template.
 */
public record AdminPromptTemplateRevisionCreateRequest(
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