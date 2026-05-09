package com.josh.interviewj.user.controller;

import com.josh.interviewj.billing.dto.request.CancelBillingOrderRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingOrderRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingRefundRequest;
import com.josh.interviewj.billing.dto.request.StartBillingOrderPaymentRequest;
import com.josh.interviewj.billing.dto.response.StartBillingOrderPaymentResponse;
import com.josh.interviewj.billing.dto.response.UserBillingEntitlementsResponse;
import com.josh.interviewj.billing.dto.response.UserBillingLedgerItemResponse;
import com.josh.interviewj.billing.dto.response.UserBillingOrderResponse;
import com.josh.interviewj.billing.dto.response.UserBillingRefundResponse;
import com.josh.interviewj.billing.dto.response.UserBillingSubscriptionResponse;
import com.josh.interviewj.billing.service.BillingEntitlementQueryService;
import com.josh.interviewj.billing.service.BillingOrderService;
import com.josh.interviewj.billing.service.BillingPaymentService;
import com.josh.interviewj.billing.service.BillingRefundService;
import com.josh.interviewj.billing.service.PaymentOrderLifecycleService;
import com.josh.interviewj.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class CurrentUserBillingController {

    private final BillingOrderService billingOrderService;
    private final BillingEntitlementQueryService billingEntitlementQueryService;
    private final PaymentOrderLifecycleService paymentOrderLifecycleService;
    private final BillingPaymentService billingPaymentService;
    private final BillingRefundService billingRefundService;

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<UserBillingOrderResponse>> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateBillingOrderRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.createOrder(authentication.getName(), request)
        ));
    }

    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<ApiResponse<UserBillingOrderResponse>> getOrder(
            Authentication authentication,
            @PathVariable String orderNo
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.getOrder(authentication.getName(), orderNo)
        ));
    }

    @GetMapping(value = "/orders", params = "status=active")
    public ResponseEntity<ApiResponse<List<UserBillingOrderResponse>>> getActiveOrders(
            Authentication authentication,
            @RequestParam String status
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.getCurrentUserActiveOrders(authentication.getName())
        ));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ResponseEntity<ApiResponse<UserBillingOrderResponse>> cancelOrder(
            Authentication authentication,
            @PathVariable String orderNo,
            @RequestBody(required = false) CancelBillingOrderRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.toUserBillingOrderResponse(
                        paymentOrderLifecycleService.cancelCurrentUserOrder(
                                authentication.getName(),
                                orderNo,
                                request == null ? null : request.getReason()
                        )
                )
        ));
    }

    @PostMapping("/orders/{orderNo}/pay")
    public ResponseEntity<ApiResponse<StartBillingOrderPaymentResponse>> startPayment(
            Authentication authentication,
            @PathVariable String orderNo,
            @Valid @RequestBody StartBillingOrderPaymentRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingPaymentService.startPayment(authentication.getName(), orderNo, request)
        ));
    }

    @PostMapping("/refund-requests")
    public ResponseEntity<ApiResponse<UserBillingRefundResponse>> createRefundRequest(
            Authentication authentication,
            @Valid @RequestBody CreateBillingRefundRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingRefundService.createRefundRequest(authentication.getName(), request)
        ));
    }

    @GetMapping("/refund-requests")
    public ResponseEntity<ApiResponse<List<UserBillingRefundResponse>>> listRefundRequests(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                billingRefundService.listCurrentUserRefundRequests(authentication.getName())
        ));
    }

    @GetMapping("/entitlements")
    public ResponseEntity<ApiResponse<UserBillingEntitlementsResponse>> getEntitlements(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                billingEntitlementQueryService.getCurrentUserEntitlements(authentication.getName())
        ));
    }

    @GetMapping("/ledger")
    public ResponseEntity<ApiResponse<Page<UserBillingLedgerItemResponse>>> getLedger(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                billingEntitlementQueryService.getCurrentUserLedger(authentication.getName(), page, size)
        ));
    }

    @GetMapping("/subscription")
    public ResponseEntity<ApiResponse<UserBillingSubscriptionResponse>> getSubscription(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                billingEntitlementQueryService.getCurrentUserSubscription(authentication.getName())
        ));
    }
}
