package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AdminPricingVersionResponse {

    private String id;
    private String provider;
    private String modelCode;
    private String usageFamily;
    private OffsetDateTime effectiveFrom;
    private OffsetDateTime effectiveTo;
    private String billingUnit;
    private String promptTokenPrice;
    private String completionTokenPrice;
    private String cachedTokenPrice;
    private String requestPrice;
    private String currency;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
