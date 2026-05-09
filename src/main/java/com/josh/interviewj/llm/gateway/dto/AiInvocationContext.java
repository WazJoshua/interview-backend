package com.josh.interviewj.llm.gateway.dto;

import com.josh.interviewj.usage.model.UsageFamily;

import java.util.Map;

public record AiInvocationContext(
        String invocationId,
        String purpose,
        UsageFamily usageFamily,
        String expectedChargeBucket,
        boolean allowFallback,
        Map<String, Object> auditMetadata
) {

    public AiInvocationContext {
        auditMetadata = auditMetadata == null ? Map.of() : Map.copyOf(auditMetadata);
    }
}
