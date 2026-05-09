package com.josh.interviewj.billing.dto.response;

public record StartBillingOrderPaymentResponse(
        String orderNo,
        String status,
        String redirectUrl
) {
}
