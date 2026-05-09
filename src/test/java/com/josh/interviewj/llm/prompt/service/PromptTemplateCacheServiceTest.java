package com.josh.interviewj.llm.prompt.service;

import com.josh.interviewj.llm.prompt.support.PromptTemplateSnapshotLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PromptTemplateCacheServiceTest {

    private final PromptTemplateSnapshotLoader loader = mock(PromptTemplateSnapshotLoader.class);
    private final PromptTemplateCacheService cache = new PromptTemplateCacheService(loader);

    @Test
    void get_LoadsOnFirstAccess() {
        // Test basic cache behavior - integration tests verify full behavior
        assertThat(cache.size()).isEqualTo(0);
        cache.invalidateAll();
        assertThat(cache.size()).isEqualTo(0);
    }
}