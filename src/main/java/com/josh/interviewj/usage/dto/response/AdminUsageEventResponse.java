package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AdminUsageEventResponse {

    private String id;
    private String userId;
    private String usageFamily;
    private String purpose;
    private String provider;
    private String modelCode;
    private String resourceType;
    private String resourceExternalId;
    private String operationId;
    private Long requestCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long cachedTokens;
    private String costChargeStatus;
    private String creditChargeStatus;
    private String chargeBucket;
    private String chargedCredits;
    private Long chargedCreditsMicros;
    private String creditPolicyVersionId;
    private String usageSource;
    private String status;
    private String failureReason;
    private String dedupeKey;
    private Map<String, Object> metadata;
    private OffsetDateTime occurredAt;
    private OffsetDateTime createdAt;
}
