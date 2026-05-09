package com.josh.interviewj.billing.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.billing.dto.request.CreateBillingPlanRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingPlanVersionRequest;
import com.josh.interviewj.billing.dto.request.CreateCreditPurchaseSkuRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingPlanResponse;
import com.josh.interviewj.billing.dto.response.AdminBillingPlanVersionResponse;
import com.josh.interviewj.billing.dto.response.AdminCreditPurchaseSkuResponse;
import com.josh.interviewj.billing.service.AdminBillingCatalogService;
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

@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
public class AdminBillingCatalogController {

    private final AdminAccessService adminAccessService;
    private final AdminBillingCatalogService adminBillingCatalogService;

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<AdminBillingPlanResponse>>> getPlans(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingCatalogService.getPlans()));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<AdminBillingPlanResponse>> createPlan(
            Authentication authentication,
            @Valid @RequestBody CreateBillingPlanRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingCatalogService.createPlan(actor.getId(), request)));
    }

    @PostMapping("/plans/{planId}/versions")
    public ResponseEntity<ApiResponse<AdminBillingPlanVersionResponse>> createPlanVersion(
            Authentication authentication,
            @PathVariable Long planId,
            @Valid @RequestBody CreateBillingPlanVersionRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingCatalogService.createPlanVersion(actor.getId(), planId, request)));
    }

    @GetMapping("/purchase-skus")
    public ResponseEntity<ApiResponse<List<AdminCreditPurchaseSkuResponse>>> getPurchaseSkus(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingCatalogService.getPurchaseSkus()));
    }

    @PostMapping("/purchase-skus")
    public ResponseEntity<ApiResponse<AdminCreditPurchaseSkuResponse>> createPurchaseSku(
            Authentication authentication,
            @Valid @RequestBody CreateCreditPurchaseSkuRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminBillingCatalogService.createPurchaseSku(actor.getId(), request)));
    }
}
