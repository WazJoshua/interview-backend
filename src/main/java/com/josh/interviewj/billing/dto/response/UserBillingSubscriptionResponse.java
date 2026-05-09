package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class UserBillingSubscriptionResponse {

    private String status;
    private String planCode;
    private String tierCode;
    private String currentBillingCycle;
    private OffsetDateTime currentPeriodStart;
    private OffsetDateTime currentPeriodEnd;
    private boolean cancelAtPeriodEnd;
    private OffsetDateTime graceUntil;
    private String provider;
    private boolean autoRenew;
}
