package com.josh.interviewj.usage.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.usage.dto.request.AdminModelCatalogQuery;
import com.josh.interviewj.usage.dto.request.AdminPricingVersionQuery;
import com.josh.interviewj.usage.dto.request.CreateModelCatalogRequest;
import com.josh.interviewj.usage.dto.request.CreatePricingVersionRequest;
import com.josh.interviewj.usage.dto.request.CreateProviderRequest;
import com.josh.interviewj.usage.dto.request.UpdateProviderRequest;
import com.josh.interviewj.usage.dto.request.UpdateRoutingPolicyRequest;
import com.josh.interviewj.usage.dto.request.UpdateModelCatalogRequest;
import com.josh.interviewj.usage.dto.response.AdminModelCatalogResponse;
import com.josh.interviewj.usage.dto.response.AdminPricingVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderDetailResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderOptionResponse;
import com.josh.interviewj.usage.dto.response.AdminRoutingPolicyResponse;
import com.josh.interviewj.usage.dto.response.LlmHealthCheckResponse;
import com.josh.interviewj.usage.service.AdminAiResourcesService;
import com.josh.interviewj.usage.service.LlmProviderHealthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAiResourcesController {

    private final AdminAccessService adminAccessService;
    private final AdminAiResourcesService adminAiResourcesService;
    private final LlmProviderHealthService llmProviderHealthService;

    @GetMapping("/llm/providers")
    public ResponseEntity<ApiResponse<List<AdminProviderOptionResponse>>> getProviders(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.getProviders()));
    }

    @GetMapping("/llm/providers/{id}")
    public ResponseEntity<ApiResponse<AdminProviderDetailResponse>> getProvider(
            Authentication authentication,
            @PathVariable String id
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.getProvider(id)));
    }

    @PostMapping("/llm/providers")
    public ResponseEntity<ApiResponse<AdminProviderDetailResponse>> createProvider(
            Authentication authentication,
            @Valid @RequestBody CreateProviderRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.createProvider(actor.getId(), request)));
    }

    @PutMapping("/llm/providers/{id}")
    public ResponseEntity<ApiResponse<AdminProviderDetailResponse>> updateProvider(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody UpdateProviderRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.updateProvider(actor.getId(), id, request)));
    }

    @DeleteMapping("/llm/providers/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteProvider(
            Authentication authentication,
            @PathVariable String id
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        adminAiResourcesService.deleteProvider(actor.getId(), id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
    }

    @GetMapping("/llm/models")
    public ResponseEntity<ApiResponse<Page<AdminModelCatalogResponse>>> getModels(
            Authentication authentication,
            @Valid @ModelAttribute AdminModelCatalogQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.getModels(query)));
    }

    @PostMapping("/llm/models")
    public ResponseEntity<ApiResponse<AdminModelCatalogResponse>> createModel(
            Authentication authentication,
            @Valid @RequestBody CreateModelCatalogRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.createModel(actor.getId(), request)));
    }

    @PutMapping("/llm/models/{id}")
    public ResponseEntity<ApiResponse<AdminModelCatalogResponse>> updateModel(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody UpdateModelCatalogRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.updateModel(actor.getId(), id, request)));
    }

    @GetMapping("/llm/pricing-versions")
    public ResponseEntity<ApiResponse<Page<AdminPricingVersionResponse>>> getPricingVersions(
            Authentication authentication,
            @Valid @ModelAttribute AdminPricingVersionQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.getPricingVersions(query)));
    }

    @PostMapping("/llm/pricing-versions")
    public ResponseEntity<ApiResponse<AdminPricingVersionResponse>> createPricingVersion(
            Authentication authentication,
            @Valid @RequestBody CreatePricingVersionRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.createPricingVersion(actor.getId(), request)));
    }

    @GetMapping("/llm/routing-policies")
    public ResponseEntity<ApiResponse<List<AdminRoutingPolicyResponse>>> getRoutingPolicies(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.getRoutingPolicies()));
    }

    @PutMapping("/llm/routing-policies/{purpose}")
    public ResponseEntity<ApiResponse<AdminRoutingPolicyResponse>> updateRoutingPolicy(
            Authentication authentication,
            @PathVariable String purpose,
            @Valid @RequestBody UpdateRoutingPolicyRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminAiResourcesService.updateRoutingPolicy(actor.getId(), purpose, request)));
    }

    @GetMapping("/llm/health")
    public ResponseEntity<ApiResponse<LlmHealthCheckResponse>> getHealth(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(llmProviderHealthService.health()));
    }

    @GetMapping("/llm/secrets/key-version-stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getSecretKeyVersionStats(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(llmProviderHealthService.secretKeyVersionStats()));
    }

    @PostMapping("/llm/cache/invalidate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> invalidateCache(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        llmProviderHealthService.invalidateLocalCache();
        return ResponseEntity.ok(ApiResponse.success(Map.of("invalidated", true)));
    }
}
