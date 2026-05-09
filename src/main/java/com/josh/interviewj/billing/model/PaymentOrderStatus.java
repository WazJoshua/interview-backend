package com.josh.interviewj.billing.model;

public enum PaymentOrderStatus {
    CREATED,
    PENDING_PROVIDER,
    AWAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELED,
    EXPIRED,
    REQUIRES_RECONCILIATION
}
