package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class UserBillingLedgerItemResponse {

    private String eventType;
    private String sourceType;
    private String sourceId;
    private Long deltaAmountMicros;
    private String deltaAmount;
    private String bucketCode;
    private OffsetDateTime occurredAt;
    private Map<String, Object> metadata;
}
