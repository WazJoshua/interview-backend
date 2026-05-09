package com.josh.interviewj.llm.prompt.service;

import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateResolution;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptTemplateResolverTest {

    private final PromptTemplateCacheService cacheService = mock(PromptTemplateCacheService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final PromptTemplateValidationService validationService = new PromptTemplateValidationService();
    private final PromptTemplateResolver resolver = new PromptTemplateResolver(cacheService, renderer, validationService);

    @Test
    void resolve_NullTemplateRef_ReturnsFallback() {
        PromptTemplateResolution result = resolver.resolve(null, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo("No template reference provided");
        assertThat(result.resolvedSystemPrompt()).isEqualTo("fallback-system");
        assertThat(result.resolvedUserPrompt()).isEqualTo("fallback-user");
    }

    @Test
    void resolve_TemplateNotFound_ReturnsFallback() {
        when(cacheService.get("missing-template")).thenReturn(null);

        PromptTemplateRef ref = new PromptTemplateRef("missing-template", Map.of());
        PromptTemplateResolution result = resolver.resolve(ref, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).contains("Template not found");
    }

    @Test
    void resolve_DisabledTemplate_ReturnsFallback() {
        PromptTemplateSnapshot disabled = new PromptTemplateSnapshot(
                "disabled-template", 1L, 1, "system", "user", List.of(), false
        );
        when(cacheService.get("disabled-template")).thenReturn(disabled);

        PromptTemplateRef ref = new PromptTemplateRef("disabled-template", Map.of());
        PromptTemplateResolution result = resolver.resolve(ref, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).contains("Template disabled");
    }

    @Test
    void resolve_MissingRequiredVariable_ReturnsFallback() {
        PromptTemplateSnapshot snapshot = new PromptTemplateSnapshot(
                "test-template", 1L, 1, "System: ${name}", "User: ${job}",
                List.of(
                        new PromptTemplateSnapshot.VariableDeclaration("name", true),
                        new PromptTemplateSnapshot.VariableDeclaration("job", true)
                ), true
        );
        when(cacheService.get("test-template")).thenReturn(snapshot);

        PromptTemplateRef ref = new PromptTemplateRef("test-template", Map.of("name", "John"));
        PromptTemplateResolution result = resolver.resolve(ref, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).contains("Variable validation failed");
    }

    @Test
    void resolve_Success_ReturnsResolvedPrompts() {
        PromptTemplateSnapshot snapshot = new PromptTemplateSnapshot(
                "test-template", 1L, 1, "System: ${name}", "User: ${job}",
                List.of(
                        new PromptTemplateSnapshot.VariableDeclaration("name", true),
                        new PromptTemplateSnapshot.VariableDeclaration("job", true)
                ), true
        );
        when(cacheService.get("test-template")).thenReturn(snapshot);

        PromptTemplateRef ref = new PromptTemplateRef("test-template", Map.of("name", "John", "job", "Engineer"));
        PromptTemplateResolution result = resolver.resolve(ref, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.templateKey()).isEqualTo("test-template");
        assertThat(result.templateRevision()).isEqualTo(1);
        assertThat(result.resolvedSystemPrompt()).isEqualTo("System: John");
        assertThat(result.resolvedUserPrompt()).isEqualTo("User: Engineer");
    }

    @Test
    void resolve_PartialTemplate_UsesFallbackForNullSide() {
        PromptTemplateSnapshot snapshot = new PromptTemplateSnapshot(
                "system-only-template", 1L, 1, "System prompt", null, List.of(), true
        );
        when(cacheService.get("system-only-template")).thenReturn(snapshot);

        PromptTemplateRef ref = new PromptTemplateRef("system-only-template", Map.of());
        PromptTemplateResolution result = resolver.resolve(ref, "fallback-system", "fallback-user");

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.resolvedSystemPrompt()).isEqualTo("System prompt");
        assertThat(result.resolvedUserPrompt()).isEqualTo("fallback-user");
    }
}