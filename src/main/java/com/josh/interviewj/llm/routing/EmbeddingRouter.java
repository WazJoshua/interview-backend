package com.josh.interviewj.llm.routing;

import com.josh.interviewj.common.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingRouter {

    private final DatabaseEmbeddingRouteResolver databaseResolver;

    @Autowired
    public EmbeddingRouter(ObjectProvider<DatabaseEmbeddingRouteResolver> databaseResolverProvider) {
        this(databaseResolverProvider.getIfAvailable());
    }

    public EmbeddingRouter(DatabaseEmbeddingRouteResolver databaseResolver) {
        this.databaseResolver = databaseResolver;
    }

    public EmbeddingRoute resolve(String purpose) {
        String normalizedPurpose = purpose == null ? null : purpose.trim();
        if (normalizedPurpose == null || normalizedPurpose.isBlank()) {
            throw new BusinessException("LLM_001", "Embedding purpose is required");
        }

        if (databaseResolver == null) {
            throw new BusinessException("LLM_001", "Embedding database routing resolver is unavailable");
        }
        var databaseRoute = databaseResolver.resolve(normalizedPurpose);
        if (databaseRoute.isPresent()) {
            return databaseRoute.get();
        }
        var invalidReason = databaseResolver.invalidReason(normalizedPurpose);
        if (invalidReason.isPresent()) {
            throw new BusinessException("LLM_001", invalidReason.get());
        }
        throw new BusinessException("LLM_001", "Embedding routing is missing in database for purpose: " + normalizedPurpose);
    }
}
