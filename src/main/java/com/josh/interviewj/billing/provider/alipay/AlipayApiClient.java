package com.josh.interviewj.billing.provider.alipay;

import com.josh.interviewj.billing.config.AlipayProperties;
import com.josh.interviewj.billing.provider.PaymentInitiationRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryResult;
import com.josh.interviewj.billing.provider.PaymentRefundRequest;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;

import java.util.Map;

public interface AlipayApiClient {

    String pagePay(PaymentInitiationRequest request, AlipayProperties properties);

    String wapPay(PaymentInitiationRequest request, AlipayProperties properties);

    VerifiedPaymentNotification verifyWebhook(String payload, Map<String, String> headers, AlipayProperties properties);

    PaymentProviderQueryResult queryPayment(PaymentProviderQueryRequest request, AlipayProperties properties);

    PaymentRefundResult refund(PaymentRefundRequest request, AlipayProperties properties);
}
