package com.josh.interviewj.llm.gateway.dto;

import java.util.List;
import java.util.Map;

public record BusinessOperationContext(
        String businessOperationId,
        Long userId,
        String resourceType,
        String resourceExternalId,
        String scenario,
        List<String> preflightChargeBuckets,
        Map<String, Object> auditMetadata
) {

    public BusinessOperationContext {
        preflightChargeBuckets = preflightChargeBuckets == null ? List.of() : List.copyOf(preflightChargeBuckets);
        auditMetadata = auditMetadata == null ? Map.of() : Map.copyOf(auditMetadata);
    }
}
