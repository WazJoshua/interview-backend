package com.josh.interviewj.billing.provider.alipay;

import com.josh.interviewj.billing.config.AlipayProperties;
import com.josh.interviewj.billing.provider.PaymentInitiationRequest;
import com.josh.interviewj.billing.provider.PaymentInitiationResult;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderQueryRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryResult;
import com.josh.interviewj.billing.provider.PaymentRefundRequest;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.ProviderWebhookResponse;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AlipayPaymentProviderAdapter implements PaymentProviderAdapter {

    private static final String TERMINAL_PC_WEB = "PC_WEB";
    private static final String TERMINAL_MOBILE_H5 = "MOBILE_H5";

    private final AlipayApiClient alipayApiClient;
    private final AlipayProperties properties;

    @Override
    public String provider() {
        return "alipay";
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationRequest request) {
        String terminal = normalizeTerminal(request.terminal());
        String redirectUrl = switch (terminal) {
            case TERMINAL_PC_WEB -> alipayApiClient.pagePay(request, properties);
            case TERMINAL_MOBILE_H5 -> alipayApiClient.wapPay(request, properties);
            default -> throw new BusinessException(ErrorCode.PAYMENT_004, "Unsupported Alipay terminal");
        };
        return new PaymentInitiationResult(request.orderNo(), redirectUrl);
    }

    @Override
    public VerifiedPaymentNotification verifyWebhook(String payload, Map<String, String> headers) {
        return alipayApiClient.verifyWebhook(payload, headers, properties);
    }

    @Override
    public PaymentProviderQueryResult queryPayment(PaymentProviderQueryRequest request) {
        return alipayApiClient.queryPayment(request, properties);
    }

    @Override
    public PaymentRefundResult refund(PaymentRefundRequest request) {
        return alipayApiClient.refund(request, properties);
    }

    @Override
    public ProviderWebhookResponse successWebhookResponse() {
        return new ProviderWebhookResponse("success", "text/plain;charset=UTF-8");
    }

    private String normalizeTerminal(String terminal) {
        return terminal == null ? "" : terminal.trim().toUpperCase(Locale.ROOT);
    }
}
