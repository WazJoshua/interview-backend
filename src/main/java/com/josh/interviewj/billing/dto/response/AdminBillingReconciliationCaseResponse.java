package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AdminBillingReconciliationCaseResponse {

    private String id;
    private String status;
    private String caseType;
    private String reasonCode;
    private String resolutionCode;
    private String paymentOrderId;
    private String paymentEventId;
    private String subscriptionContractId;
    private String userId;
    private Map<String, Object> details;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
