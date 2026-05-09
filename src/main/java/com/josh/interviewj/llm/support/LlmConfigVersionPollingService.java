package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmRuntimeProperties;
import com.josh.interviewj.llm.prompt.service.PromptTemplateCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.llm.runtime.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class LlmConfigVersionPollingService {

    private final LlmRuntimeProperties llmRuntimeProperties;
    private final LlmConfigCacheService llmConfigCacheService;
    private final PromptTemplateCacheService promptTemplateCacheService;

    @Scheduled(fixedDelayString = "#{@llmRuntimeProperties.refresh.pollInterval.toMillis()}")
    public void refreshIfVersionChanged() {
        if (!llmRuntimeProperties.getRefresh().isEnabled()) {
            return;
        }
        try {
            // First invalidate prompt cache when version changed, then refresh route cache
            // refreshIfVersionChanged returns true if cache was refreshed
            boolean refreshed = llmConfigCacheService.refreshIfVersionChanged();
            if (refreshed) {
                promptTemplateCacheService.invalidateAll();
                log.info("prompt_template_cache_invalidated_due_to_version_change");
            }
        } catch (RuntimeException exception) {
            log.error("llm_config_version_poll_failed message={}", exception.getMessage());
        }
    }
}