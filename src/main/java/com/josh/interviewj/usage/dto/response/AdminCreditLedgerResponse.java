package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AdminCreditLedgerResponse {

    private String id;
    private String usageEventId;
    private String userId;
    private String purpose;
    private String usageFamily;
    private String provider;
    private String modelCode;
    private String chargeBucket;
    private String chargeStatus;
    private String chargedCredits;
    private Long chargedCreditsMicros;
    private String creditPolicyVersionId;
    private OffsetDateTime createdAt;
}
