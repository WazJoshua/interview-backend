package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class UserBillingRefundResponse {

    private String id;
    private String orderNo;
    private String requestedAmount;
    private String currency;
    private String reason;
    private String status;
    private String reviewComment;
    private String providerRefundRef;
    private String providerStatus;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime refundedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
