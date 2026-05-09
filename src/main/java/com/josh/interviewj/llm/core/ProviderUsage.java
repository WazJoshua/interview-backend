package com.josh.interviewj.llm.core;

import com.josh.interviewj.usage.model.UsageFamily;

public record ProviderUsage(
        UsageFamily usageFamily,
        Long requestCount,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        Long cachedTokens
) {
}
