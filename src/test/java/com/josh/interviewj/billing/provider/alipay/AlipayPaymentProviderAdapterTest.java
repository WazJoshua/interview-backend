package com.josh.interviewj.billing.provider.alipay;

import com.josh.interviewj.billing.config.AlipayProperties;
import com.josh.interviewj.billing.provider.PaymentInitiationRequest;
import com.josh.interviewj.billing.provider.PaymentInitiationResult;
import com.josh.interviewj.billing.provider.PaymentProviderQueryRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryResult;
import com.josh.interviewj.billing.provider.PaymentRefundRequest;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.ProviderWebhookResponse;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlipayPaymentProviderAdapterTest {

    @Mock
    private AlipayApiClient alipayApiClient;

    private AlipayProperties properties;
    private AlipayPaymentProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new AlipayProperties();
        properties.setAppId("202600000000001");
        properties.setMerchantPrivateKey("merchant-private-key");
        properties.setAlipayPublicKey("alipay-public-key");
        properties.setNotifyUrl("https://api.example.com/api/v1/payment/webhooks/alipay");
        properties.setReturnUrl("https://app.example.com/payments/result");
        properties.setGateway("https://openapi.alipay.com");
        properties.setPagePayProductMode("FAST_INSTANT_TRADE_PAY");
        properties.setWapScene("bar_code");
        adapter = new AlipayPaymentProviderAdapter(alipayApiClient, properties);
    }

    @Test
    void initiatePayment_WhenPcWeb_ReturnsRedirectUrl() {
        PaymentInitiationRequest request = new PaymentInitiationRequest(
                "po_123",
                new BigDecimal("29.900000"),
                "USD",
                "PC_WEB",
                "InterviewJ Plus",
                "https://app.example.com/payments/result"
        );
        when(alipayApiClient.pagePay(request, properties)).thenReturn("https://openapi.alipay.com/gateway.do?page");

        PaymentInitiationResult result = adapter.initiatePayment(request);

        assertThat(result.providerOrderRef()).isEqualTo("po_123");
        assertThat(result.redirectUrl()).isEqualTo("https://openapi.alipay.com/gateway.do?page");
        verify(alipayApiClient).pagePay(request, properties);
    }

    @Test
    void initiatePayment_WhenMobileH5_ReturnsRedirectUrl() {
        PaymentInitiationRequest request = new PaymentInitiationRequest(
                "po_456",
                new BigDecimal("10.000000"),
                "USD",
                "MOBILE_H5",
                "InterviewJ Credits",
                null
        );
        when(alipayApiClient.wapPay(request, properties)).thenReturn("https://openapi.alipay.com/gateway.do?wap");

        PaymentInitiationResult result = adapter.initiatePayment(request);

        assertThat(result.providerOrderRef()).isEqualTo("po_456");
        assertThat(result.redirectUrl()).isEqualTo("https://openapi.alipay.com/gateway.do?wap");
        verify(alipayApiClient).wapPay(request, properties);
    }

    @Test
    void verifyWebhook_WhenSignatureValid_ReturnsVerifiedNotification() {
        String payload = "out_trade_no=po_123&trade_no=trade_123&trade_status=TRADE_SUCCESS";
        Map<String, String> headers = Map.of("content-type", "application/x-www-form-urlencoded");
        VerifiedPaymentNotification expected = new VerifiedPaymentNotification(
                "alipay",
                "notify_123",
                "po_123",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 10, 0),
                payload,
                Map.of("tradeNo", "trade_123")
        );
        when(alipayApiClient.verifyWebhook(payload, headers, properties)).thenReturn(expected);

        VerifiedPaymentNotification actual = adapter.verifyWebhook(payload, headers);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void getWebhookResponse_WhenHandled_ReturnsSuccessText() {
        ProviderWebhookResponse response = adapter.successWebhookResponse();

        assertThat(response.body()).isEqualTo("success");
        assertThat(response.contentType()).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    void queryPayment_WhenTradeSucceeded_ReturnsSucceededState() {
        PaymentProviderQueryRequest request = new PaymentProviderQueryRequest("po_123", "po_123");
        PaymentProviderQueryResult expected = new PaymentProviderQueryResult(
                "po_123",
                "trade_123",
                "PAYMENT_SUCCEEDED",
                "SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 10, 5),
                Map.of("tradeStatus", "TRADE_SUCCESS")
        );
        when(alipayApiClient.queryPayment(request, properties)).thenReturn(expected);

        PaymentProviderQueryResult actual = adapter.queryPayment(request);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void refund_WhenTradeRefunded_ReturnsRefundResult() {
        PaymentRefundRequest request = new PaymentRefundRequest(
                "po_123",
                "po_123",
                "refund_123",
                new BigDecimal("29.900000"),
                "USD",
                "customer request"
        );
        PaymentRefundResult expected = new PaymentRefundResult(
                "refund_trade_123",
                "REFUND_SUCCESS",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 10, 6),
                Map.of("tradeNo", "trade_123")
        );
        when(alipayApiClient.refund(request, properties)).thenReturn(expected);

        PaymentRefundResult actual = adapter.refund(request);

        assertThat(actual).isEqualTo(expected);
    }
}
