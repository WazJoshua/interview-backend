package com.josh.interviewj.usage.dto.response;

import java.time.LocalDateTime;

/**
 * Response for a single revision.
 */
public record AdminPromptTemplateRevisionResponse(
        Long id,
        Integer revisionNo,
        String systemTemplate,
        String userTemplate,
        String variables,
        String changeNote,
        LocalDateTime createdAt,
        String createdBy
) {}