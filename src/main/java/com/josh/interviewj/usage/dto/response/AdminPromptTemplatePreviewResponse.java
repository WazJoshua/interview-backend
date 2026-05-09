package com.josh.interviewj.usage.dto.response;

/**
 * Response for preview rendering.
 */
public record AdminPromptTemplatePreviewResponse(
        String renderedSystemPrompt,
        String renderedUserPrompt,
        boolean success,
        String errorMessage
) {}