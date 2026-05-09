package com.josh.interviewj.billing.model;

public enum PaymentEventProcessStatus {
    RECEIVED,
    VERIFIED,
    APPLYING,
    APPLIED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL
}
