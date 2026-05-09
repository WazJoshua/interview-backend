package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class UserBillingOrderResponse {

    private String orderNo;
    private String status;
    private String provider;
    private String orderType;
    private String bizRefType;
    private String bizRefId;
    private String amount;
    private String currency;
    private OffsetDateTime expiresAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime payableActivatedAt;
    private boolean reused;
    private Map<String, Object> pricingSnapshot;
}
