package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AdminCreditPolicyVersionResponse {

    private String id;
    private String purpose;
    private String chargeBucket;
    private String usageFamily;
    private OffsetDateTime effectiveFrom;
    private OffsetDateTime effectiveTo;
    private String billingUnit;
    private String promptTokenRatio;
    private String completionTokenRatio;
    private String cachedTokenRatio;
    private String requestRatio;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
