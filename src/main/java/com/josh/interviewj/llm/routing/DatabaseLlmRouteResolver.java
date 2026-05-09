package com.josh.interviewj.llm.routing;

import com.josh.interviewj.llm.support.LlmConfigCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DatabaseLlmRouteResolver {

    private final LlmConfigCacheService llmConfigCacheService;

    public Optional<LlmRoute> resolve(String purpose) {
        return Optional.ofNullable(llmConfigCacheService.getSnapshot().llmRoutes().get(purpose));
    }

    public Optional<String> invalidReason(String purpose) {
        return Optional.ofNullable(llmConfigCacheService.getSnapshot().invalidPurposes().get(purpose));
    }
}
