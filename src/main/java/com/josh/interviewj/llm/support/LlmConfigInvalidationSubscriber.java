package com.josh.interviewj.llm.support;

import com.josh.interviewj.llm.prompt.service.PromptTemplateCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
public class LlmConfigInvalidationSubscriber implements MessageListener {

    private final LlmConfigCacheService llmConfigCacheService;
    private final PromptTemplateCacheService promptTemplateCacheService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            return;
        }

        llmConfigCacheService.invalidate();
        promptTemplateCacheService.invalidateAll();
        log.info("llm_config_cache_invalidated payload={}", new String(message.getBody(), StandardCharsets.UTF_8));
    }
}