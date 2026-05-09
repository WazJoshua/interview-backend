package com.josh.interviewj.llm.prompt.dto;

/**
 * Result of prompt template resolution.
 * Contains the resolved prompts and metadata for auditing.
 */
public record PromptTemplateResolution(
        String resolvedSystemPrompt,
        String resolvedUserPrompt,
        String templateKey,
        Integer templateRevision,
        boolean fallbackUsed,
        String fallbackReason
) {
    /**
     * Create a resolution indicating fallback was used.
     *
     * @param systemPrompt fallback system prompt
     * @param userPrompt fallback user prompt
     * @param templateKey the template key that triggered fallback (may be null if no ref provided)
     * @param reason the reason for fallback
     */
    public static PromptTemplateResolution fallback(
            String systemPrompt,
            String userPrompt,
            String templateKey,
            String reason
    ) {
        return new PromptTemplateResolution(
                systemPrompt,
                userPrompt,
                templateKey,
                null,
                true,
                reason
        );
    }

    /**
     * Create a resolution indicating fallback was used (backward-compatible overload).
     * Use when templateKey is genuinely unknown (e.g., no template reference provided).
     */
    public static PromptTemplateResolution fallback(
            String systemPrompt,
            String userPrompt,
            String reason
    ) {
        return fallback(systemPrompt, userPrompt, null, reason);
    }

    /**
     * Create a resolution from successful template rendering.
     */
    public static PromptTemplateResolution fromTemplate(
            String systemPrompt,
            String userPrompt,
            String templateKey,
            Integer revisionNo
    ) {
        return new PromptTemplateResolution(
                systemPrompt,
                userPrompt,
                templateKey,
                revisionNo,
                false,
                null
        );
    }
}