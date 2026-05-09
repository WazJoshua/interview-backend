package com.josh.interviewj.billing.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.billing.dto.request.AdminManualAdjustmentRequest;
import com.josh.interviewj.billing.dto.request.AdminOrderOperationRequest;
import com.josh.interviewj.billing.dto.request.AdminReconciliationDecisionRequest;
import com.josh.interviewj.billing.dto.request.AdminReviewBillingRefundRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingRefundResponse;
import com.josh.interviewj.billing.dto.response.AdminBillingReconciliationCaseResponse;
import com.josh.interviewj.billing.service.AdminBillingOperationsService;
import com.josh.interviewj.billing.service.BillingReconciliationService;
import com.josh.interviewj.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
public class AdminBillingOperationsController {

    private final AdminAccessService adminAccessService;
    private final BillingReconciliationService billingReconciliationService;
    private final AdminBillingOperationsService adminBillingOperationsService;

    @GetMapping("/reconciliation-cases")
    public ResponseEntity<ApiResponse<List<AdminBillingReconciliationCaseResponse>>> getOpenCases(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(billingReconciliationService.getOpenCases()));
    }

    @PostMapping("/reconciliation-cases/{caseId}/decisions")
    public ResponseEntity<ApiResponse<AdminBillingReconciliationCaseResponse>> resolveCase(
            Authentication authentication,
            @PathVariable Long caseId,
            @Valid @RequestBody AdminReconciliationDecisionRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(billingReconciliationService.resolveCase(actor.getId(), caseId, request)));
    }

    @PostMapping("/manual-adjustments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createManualAdjustment(
            Authentication authentication,
            @Valid @RequestBody AdminManualAdjustmentRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingOperationsService.createManualAdjustment(actor.getId(), request)));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelOrder(
            Authentication authentication,
            @PathVariable Long orderId,
            @Valid @RequestBody AdminOrderOperationRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingOperationsService.cancelOrder(actor.getId(), orderId, request.getReason())));
    }

    @PostMapping("/orders/{orderId}/fail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markOrderFailed(
            Authentication authentication,
            @PathVariable Long orderId,
            @Valid @RequestBody AdminOrderOperationRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingOperationsService.markOrderFailed(actor.getId(), orderId, request.getReason())));
    }

    @GetMapping("/refund-requests")
    public ResponseEntity<ApiResponse<List<AdminBillingRefundResponse>>> listPendingRefundRequests(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingOperationsService.listPendingRefundRequests()));
    }

    @PostMapping("/refund-requests/{refundRequestId}/review")
    public ResponseEntity<ApiResponse<AdminBillingRefundResponse>> reviewRefundRequest(
            Authentication authentication,
            @PathVariable Long refundRequestId,
            @Valid @RequestBody AdminReviewBillingRefundRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                adminBillingOperationsService.reviewRefundRequest(actor.getId(), refundRequestId, request)
        ));
    }
}
