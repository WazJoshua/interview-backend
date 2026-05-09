package com.josh.interviewj.llm.routing;

import com.josh.interviewj.common.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the configured provider and model for a requested LLM purpose.
 */
@Component
public class LlmRouter {

    private final DatabaseLlmRouteResolver databaseResolver;

    @Autowired
    public LlmRouter(ObjectProvider<DatabaseLlmRouteResolver> databaseResolverProvider) {
        this(databaseResolverProvider.getIfAvailable());
    }

    public LlmRouter(DatabaseLlmRouteResolver databaseResolver) {
        this.databaseResolver = databaseResolver;
    }

    /**
     * Resolves the provider route for a given purpose.
     *
     * @param purpose requested LLM purpose
     * @return resolved route
     */
    public LlmRoute resolve(String purpose) {
        String normalizedPurpose = purpose == null ? null : purpose.trim();
        if (normalizedPurpose == null || normalizedPurpose.isBlank()) {
            throw new BusinessException("LLM_001", "LLM purpose is required");
        }

        if (databaseResolver == null) {
            throw new BusinessException("LLM_001", "LLM database routing resolver is unavailable");
        }
        var databaseRoute = databaseResolver.resolve(normalizedPurpose);
        if (databaseRoute.isPresent()) {
            return databaseRoute.get();
        }
        var invalidReason = databaseResolver.invalidReason(normalizedPurpose);
        if (invalidReason.isPresent()) {
            throw new BusinessException("LLM_001", invalidReason.get());
        }
        throw new BusinessException("LLM_001", "LLM routing is missing in database for purpose: " + normalizedPurpose);
    }
}
