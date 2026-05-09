package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;

public record BillingUsageDebitResult(
        BillingEvent billingEvent,
        long subscriptionAllocatedMicros,
        long purchasedAllocatedMicros
) {
}
