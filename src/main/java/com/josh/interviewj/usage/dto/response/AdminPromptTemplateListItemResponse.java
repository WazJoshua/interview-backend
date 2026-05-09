package com.josh.interviewj.usage.dto.response;

import java.time.LocalDateTime;

/**
 * List item for prompt template listing.
 */
public record AdminPromptTemplateListItemResponse(
        String templateKey,
        String domain,
        String purpose,
        String invocationKind,
        String description,
        Boolean enabled,
        Integer activeRevisionNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy
) {}