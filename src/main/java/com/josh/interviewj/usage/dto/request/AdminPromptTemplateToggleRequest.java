package com.josh.interviewj.usage.dto.request;

/**
 * Request to toggle template enabled status.
 */
public record AdminPromptTemplateToggleRequest(
        Boolean enabled,
        String updatedBy
) {}