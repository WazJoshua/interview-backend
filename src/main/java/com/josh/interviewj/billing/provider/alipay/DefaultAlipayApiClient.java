package com.josh.interviewj.billing.provider.alipay;

import com.alipay.v3.ApiClient;
import com.alipay.v3.ApiException;
import com.alipay.v3.api.AlipayTradeApi;
import com.alipay.v3.api.AlipayTradeRefundApi;
import com.alipay.v3.model.AlipayTradeQueryModel;
import com.alipay.v3.model.AlipayTradeQueryResponseModel;
import com.alipay.v3.model.AlipayTradeRefundApplyModel;
import com.alipay.v3.model.AlipayTradeRefundApplyResponseModel;
import com.alipay.v3.util.AlipaySignature;
import com.alipay.v3.util.model.AlipayConfig;
import com.josh.interviewj.billing.config.AlipayProperties;
import com.josh.interviewj.billing.provider.PaymentInitiationRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryResult;
import com.josh.interviewj.billing.provider.PaymentRefundRequest;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DefaultAlipayApiClient implements AlipayApiClient {

    private static final DateTimeFormatter ALIPAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String METHOD_PAGE_PAY = "alipay.trade.page.pay";
    private static final String METHOD_WAP_PAY = "alipay.trade.wap.pay";
    private static final String WAP_PRODUCT_CODE = "QUICK_WAP_WAY";

    private final ObjectMapper objectMapper;

    @Override
    public String pagePay(PaymentInitiationRequest request, AlipayProperties properties) {
        return buildRedirectUrl(request, properties, METHOD_PAGE_PAY, properties.getPagePayProductMode(), false);
    }

    @Override
    public String wapPay(PaymentInitiationRequest request, AlipayProperties properties) {
        return buildRedirectUrl(request, properties, METHOD_WAP_PAY, WAP_PRODUCT_CODE, true);
    }

    @Override
    public VerifiedPaymentNotification verifyWebhook(
            String payload,
            Map<String, String> headers,
            AlipayProperties properties
    ) {
        try {
            requireConfigured(properties);
            Map<String, String> params = parseFormEncodedPayload(payload);
            boolean verified = AlipaySignature.verifyV1(params, properties.getAlipayPublicKey(), "UTF-8", "RSA2");
            if (!verified) {
                throw new BusinessException(ErrorCode.PAYMENT_001, "Invalid Alipay signature");
            }
            String tradeStatus = params.get("trade_status");
            return new VerifiedPaymentNotification(
                    "alipay",
                    firstNonBlank(params.get("notify_id"), params.get("trade_no"), params.get("out_trade_no")),
                    params.get("out_trade_no"),
                    null,
                    mapTradeStatusToEventType(tradeStatus),
                    parseAmount(firstNonBlank(params.get("total_amount"), params.get("receipt_amount"))),
                    firstNonBlank(params.get("trans_currency"), params.get("settle_currency"), "CNY"),
                    parseDateTime(firstNonBlank(params.get("gmt_payment"), params.get("notify_time"), params.get("gmt_refund"))),
                    payload,
                    Map.of(
                            "tradeNo", params.get("trade_no"),
                            "tradeStatus", tradeStatus
                    )
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "Failed to verify Alipay webhook");
        }
    }

    @Override
    public PaymentProviderQueryResult queryPayment(PaymentProviderQueryRequest request, AlipayProperties properties) {
        try {
            ApiClient apiClient = buildApiClient(properties);
            AlipayTradeApi tradeApi = new AlipayTradeApi(apiClient);
            AlipayTradeQueryResponseModel response = tradeApi.query(new AlipayTradeQueryModel()
                    .outTradeNo(firstNonBlank(request.providerOrderRef(), request.orderNo())));
            String tradeStatus = response.getTradeStatus();
            return new PaymentProviderQueryResult(
                    firstNonBlank(response.getOutTradeNo(), request.providerOrderRef(), request.orderNo()),
                    response.getTradeNo(),
                    mapTradeStatusToEventType(tradeStatus),
                    mapTradeStatusToProviderStatus(tradeStatus),
                    parseAmount(firstNonBlank(response.getPayAmount(), response.getTotalAmount(), response.getReceiptAmount())),
                    firstNonBlank(response.getPayCurrency(), response.getReceiptCurrencyType(), "CNY"),
                    parseDateTime(response.getSendPayDate()),
                    Map.of("tradeStatus", tradeStatus)
            );
        } catch (ApiException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Failed to query Alipay trade");
        }
    }

    @Override
    public PaymentRefundResult refund(PaymentRefundRequest request, AlipayProperties properties) {
        try {
            ApiClient apiClient = buildApiClient(properties);
            AlipayTradeRefundApi refundApi = new AlipayTradeRefundApi(apiClient);
            AlipayTradeRefundApplyResponseModel response = refundApi.apply(new AlipayTradeRefundApplyModel()
                    .outTradeNo(firstNonBlank(request.providerOrderRef(), request.orderNo()))
                    .outRequestNo(request.refundRequestId())
                    .refundAmount(formatAmount(request.amount()))
                    .refundReason(request.reason())
                    .notifyUrl(properties.getNotifyUrl()));
            return new PaymentRefundResult(
                    response.getOutRequestNo(),
                    response.getRefundStatus(),
                    parseAmount(response.getRefundAmount()),
                    request.currency(),
                    null,
                    Map.of("tradeNo", response.getTradeNo())
            );
        } catch (ApiException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Failed to refund Alipay trade");
        }
    }

    private String buildRedirectUrl(
            PaymentInitiationRequest request,
            AlipayProperties properties,
            String method,
            String productCode,
            boolean mobileH5
    ) {
        try {
            ApiClient apiClient = buildApiClient(properties);
            Map<String, Object> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", request.orderNo());
            bizContent.put("total_amount", formatAmount(request.amount()));
            bizContent.put("subject", firstNonBlank(request.subject(), "Billing order " + request.orderNo()));
            bizContent.put("product_code", productCode);
            if (mobileH5 && properties.getWapScene() != null && !properties.getWapScene().isBlank()) {
                bizContent.put("scene", properties.getWapScene());
            }

            Map<String, String> appParams = new LinkedHashMap<>();
            appParams.put("biz_content", objectMapper.writeValueAsString(bizContent));

            Map<String, String> systemParams = new LinkedHashMap<>();
            systemParams.put("notify_url", properties.getNotifyUrl());
            systemParams.put("return_url", firstNonBlank(request.returnUrl(), properties.getReturnUrl()));

            Map<String, String> sortedMap = apiClient.getSortedMap(method, appParams, systemParams);
            return apiClient.buildQuery(sortedMap, resolveGatewayUrl(properties.getGateway()), true);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Failed to build Alipay redirect url");
        }
    }

    private ApiClient buildApiClient(AlipayProperties properties) {
        requireConfigured(properties);
        ApiClient apiClient = new ApiClient();
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl(resolveBasePath(properties.getGateway()));
        alipayConfig.setAppId(properties.getAppId());
        alipayConfig.setPrivateKey(properties.getMerchantPrivateKey());
        alipayConfig.setAlipayPublicKey(properties.getAlipayPublicKey());
        try {
            apiClient.setAlipayConfig(alipayConfig);
            return apiClient;
        } catch (ApiException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Failed to initialize Alipay SDK client");
        }
    }

    private void requireConfigured(AlipayProperties properties) {
        if (isBlank(properties.getAppId())
                || isBlank(properties.getMerchantPrivateKey())
                || isBlank(properties.getAlipayPublicKey())
                || isBlank(properties.getNotifyUrl())
                || isBlank(properties.getGateway())) {
            throw new IllegalStateException("Alipay properties are incomplete");
        }
    }

    private Map<String, String> parseFormEncodedPayload(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return values;
        }
        for (String pair : payload.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                values.put(decode(pair), "");
                continue;
            }
            values.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String resolveBasePath(String gateway) {
        String normalized = gateway == null ? "" : gateway.trim();
        return normalized.endsWith("/gateway.do")
                ? normalized.substring(0, normalized.length() - "/gateway.do".length())
                : normalized;
    }

    private String resolveGatewayUrl(String gateway) {
        String normalized = gateway == null ? "" : gateway.trim();
        return normalized.endsWith("/gateway.do") ? normalized : normalized + "/gateway.do";
    }

    private String mapTradeStatusToEventType(String tradeStatus) {
        return switch (firstNonBlank(tradeStatus, "")) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "PAYMENT_SUCCEEDED";
            case "WAIT_BUYER_PAY" -> "PAYMENT_PENDING";
            case "TRADE_CLOSED" -> "PAYMENT_CLOSED";
            default -> "PAYMENT_UNKNOWN";
        };
    }

    private String mapTradeStatusToProviderStatus(String tradeStatus) {
        return switch (firstNonBlank(tradeStatus, "")) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
            case "WAIT_BUYER_PAY" -> "PENDING";
            case "TRADE_CLOSED" -> "CLOSED";
            default -> "UNKNOWN";
        };
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    private BigDecimal parseAmount(String amount) {
        return amount == null || amount.isBlank() ? null : new BigDecimal(amount);
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, ALIPAY_DATE_TIME);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
