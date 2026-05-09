package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmRuntimeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmConfigInvalidationPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LlmRuntimeProperties llmRuntimeProperties;
    private final LlmConfigCacheService llmConfigCacheService;

    public void publishInvalidation(Long configVersion, String changeType, String payload) {
        if (!llmRuntimeProperties.getInvalidation().isRedisEnabled()) {
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("configVersion", configVersion);
        message.put("changeType", changeType);
        message.put("payload", payload);
        message.put("publishedAt", Instant.now().toString());

        try {
            stringRedisTemplate.convertAndSend(
                    llmRuntimeProperties.getInvalidation().getChannel(),
                    objectMapper.writeValueAsString(message)
            );
            llmConfigCacheService.invalidate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish LLM config invalidation", exception);
        }
    }
}
