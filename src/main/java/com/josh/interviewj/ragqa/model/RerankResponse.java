package com.josh.interviewj.ragqa.model;

import com.josh.interviewj.llm.core.ProviderUsage;

import java.util.List;

public record RerankResponse(
        String model,
        Integer promptTokens,
        List<ScoredDocument> results,
        int totalTokens,
        ProviderUsage usage
) {
    public RerankResponse(String model, Integer promptTokens, List<ScoredDocument> results, int totalTokens) {
        this(model, promptTokens, results, totalTokens, null);
    }

    public RerankResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record ScoredDocument(
            int index,
            double relevanceScore
    ) {
    }
}
