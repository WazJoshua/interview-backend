package com.josh.interviewj.usage.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.request.AdminCreditPolicyVersionQuery;
import com.josh.interviewj.usage.dto.request.CreateCreditPolicyVersionRequest;
import com.josh.interviewj.usage.dto.request.UpdateUserCreditPolicyRequest;
import com.josh.interviewj.usage.dto.response.AdminCreditLedgerResponse;
import com.josh.interviewj.usage.dto.response.AdminCreditPolicyVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminUserCreditPolicyResponse;
import com.josh.interviewj.usage.service.AdminCreditsBillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminCreditsBillingController {

    private final AdminAccessService adminAccessService;
    private final AdminCreditsBillingService adminCreditsBillingService;

    @GetMapping("/credits/policy-versions")
    public ResponseEntity<ApiResponse<Page<AdminCreditPolicyVersionResponse>>> getPolicyVersions(
            Authentication authentication,
            @Valid @ModelAttribute AdminCreditPolicyVersionQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminCreditsBillingService.getPolicyVersions(query)));
    }

    @PostMapping("/credits/policy-versions")
    public ResponseEntity<ApiResponse<AdminCreditPolicyVersionResponse>> createPolicyVersion(
            Authentication authentication,
            @Valid @RequestBody CreateCreditPolicyVersionRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminCreditsBillingService.createPolicyVersion(actor.getId(), request)));
    }

    @GetMapping("/credits/ledger")
    public ResponseEntity<ApiResponse<Page<AdminCreditLedgerResponse>>> getCreditLedger(
            Authentication authentication,
            @Valid @ModelAttribute AdminUsageEventsQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminCreditsBillingService.getCreditLedger(query)));
    }

    @GetMapping("/users/{userId}/credit-policy")
    public ResponseEntity<ApiResponse<AdminUserCreditPolicyResponse>> getUserCreditPolicy(
            Authentication authentication,
            @PathVariable UUID userId
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminCreditsBillingService.getUserCreditPolicy(userId)));
    }

    @PutMapping("/users/{userId}/credit-policy")
    public ResponseEntity<ApiResponse<AdminUserCreditPolicyResponse>> updateUserCreditPolicy(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserCreditPolicyRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminCreditsBillingService.updateUserCreditPolicy(actor.getId(), userId, request)));
    }
}
