package com.josh.interviewj.billing.provider;

import java.util.Map;

public interface PaymentProviderAdapter {

    String provider();

    PaymentInitiationResult initiatePayment(PaymentInitiationRequest request);

    VerifiedPaymentNotification verifyWebhook(String payload, Map<String, String> headers);

    PaymentProviderQueryResult queryPayment(PaymentProviderQueryRequest request);

    PaymentRefundResult refund(PaymentRefundRequest request);

    ProviderWebhookResponse successWebhookResponse();
}
