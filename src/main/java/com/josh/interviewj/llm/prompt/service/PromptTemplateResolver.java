package com.josh.interviewj.llm.prompt.service;

import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateResolution;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Main service for resolving prompt templates at runtime.
 * Handles fallback logic and partial template coverage.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptTemplateResolver {

    private final PromptTemplateCacheService cacheService;
    private final PromptTemplateRenderer renderer;
    private final PromptTemplateValidationService validationService;

    /**
     * Resolve prompts from template reference with fallback.
     *
     * @param templateRef template reference from invocation input
     * @param fallbackSystemPrompt Java fallback system prompt
     * @param fallbackUserPrompt Java fallback user prompt
     * @return resolution result with prompts and metadata
     */
    public PromptTemplateResolution resolve(
            PromptTemplateRef templateRef,
            String fallbackSystemPrompt,
            String fallbackUserPrompt
    ) {
        if (templateRef == null) {
            return PromptTemplateResolution.fallback(
                    fallbackSystemPrompt,
                    fallbackUserPrompt,
                    "No template reference provided"
            );
        }

        String templateKey = templateRef.templateKey();
        Map<String, Object> variables = templateRef.variables();

        try {
            PromptTemplateSnapshot snapshot = cacheService.get(templateKey);
            if (snapshot == null) {
                return PromptTemplateResolution.fallback(
                        fallbackSystemPrompt,
                        fallbackUserPrompt,
                        templateKey,
                        "Template not found: " + templateKey
                );
            }

            if (!snapshot.enabled()) {
                return PromptTemplateResolution.fallback(
                        fallbackSystemPrompt,
                        fallbackUserPrompt,
                        templateKey,
                        "Template disabled: " + templateKey
                );
            }

            // Validate required variables
            String validationError = validationService.validateRuntimeVariables(snapshot, variables);
            if (validationError != null) {
                return PromptTemplateResolution.fallback(
                        fallbackSystemPrompt,
                        fallbackUserPrompt,
                        templateKey,
                        "Variable validation failed: " + validationError
                );
            }

            // Render templates (partial coverage allowed)
            String resolvedSystemPrompt;
            String resolvedUserPrompt;

            try {
                // Render system template if present, otherwise use fallback
                if (snapshot.systemTemplate() != null) {
                    resolvedSystemPrompt = renderer.render(snapshot.systemTemplate(), variables);
                } else {
                    resolvedSystemPrompt = fallbackSystemPrompt;
                }

                // Render user template if present, otherwise use fallback
                if (snapshot.userTemplate() != null) {
                    resolvedUserPrompt = renderer.render(snapshot.userTemplate(), variables);
                } else {
                    resolvedUserPrompt = fallbackUserPrompt;
                }

                return PromptTemplateResolution.fromTemplate(
                        resolvedSystemPrompt,
                        resolvedUserPrompt,
                        templateKey,
                        snapshot.revisionNo()
                );
            } catch (IllegalArgumentException e) {
                return PromptTemplateResolution.fallback(
                        fallbackSystemPrompt,
                        fallbackUserPrompt,
                        templateKey,
                        "Template rendering failed: " + e.getMessage()
                );
            }
        } catch (Exception e) {
            log.error("Error resolving template: {}", templateKey, e);
            return PromptTemplateResolution.fallback(
                    fallbackSystemPrompt,
                    fallbackUserPrompt,
                    templateKey,
                    "Template resolution error: " + e.getClass().getSimpleName()
            );
        }
    }
}