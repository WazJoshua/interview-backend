package com.josh.interviewj.usage.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail response for a single prompt template.
 */
public record AdminPromptTemplateDetailResponse(
        String templateKey,
        String domain,
        String purpose,
        String invocationKind,
        String description,
        Boolean enabled,
        Integer activeRevisionNo,
        String activeSystemTemplate,
        String activeUserTemplate,
        String activeVariables,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        List<RevisionSummary> revisions
) {
    public record RevisionSummary(
            Integer revisionNo,
            String changeNote,
            LocalDateTime createdAt,
            String createdBy
    ) {}
}