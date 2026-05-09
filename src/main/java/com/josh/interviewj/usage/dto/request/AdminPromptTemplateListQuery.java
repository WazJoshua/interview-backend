package com.josh.interviewj.usage.dto.request;

/**
 * Query parameters for listing prompt templates.
 */
public record AdminPromptTemplateListQuery(
        String domain,
        String purpose,
        Boolean enabled
) {}