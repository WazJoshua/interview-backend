package com.josh.interviewj.billing.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.billing.dto.request.CreateBillingPlanRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingPlanVersionRequest;
import com.josh.interviewj.billing.dto.request.CreateCreditPurchaseSkuRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingPlanResponse;
import com.josh.interviewj.billing.dto.response.AdminBillingPlanVersionResponse;
import com.josh.interviewj.billing.dto.response.AdminCreditPurchaseSkuResponse;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.CreditPurchaseSku;
import com.josh.interviewj.billing.model.CreditPurchaseSkuVersion;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuVersionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.service.CreditFormattingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminBillingCatalogService {

    private final ObjectMapper objectMapper;
    private final CreditFormattingService creditFormattingService;
    private final AdminOperationLogService adminOperationLogService;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingPlanEntitlementItemRepository entitlementItemRepository;
    private final CreditPurchaseSkuRepository creditPurchaseSkuRepository;
    private final CreditPurchaseSkuVersionRepository creditPurchaseSkuVersionRepository;

    public List<AdminBillingPlanResponse> getPlans() {
        List<BillingPlanVersion> versions = billingPlanVersionRepository.findAll();
        List<BillingPlanEntitlementItem> entitlementItems = entitlementItemRepository.findAll();
        return billingPlanRepository.findAll().stream()
                .sorted(Comparator.comparing(BillingPlan::getPlanCode))
                .map(plan -> toPlanResponse(plan, versions, entitlementItems))
                .toList();
    }

    @Transactional
    public AdminBillingPlanResponse createPlan(Long actorUserId, CreateBillingPlanRequest request) {
        billingPlanRepository.findByPlanCode(request.getPlanCode()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_002, "Billing plan code already exists");
        });

        BillingPlan plan = billingPlanRepository.save(BillingPlan.builder()
                .externalId(UUID.randomUUID())
                .planCode(request.getPlanCode())
                .tierCode(request.getTierCode())
                .displayName(request.getDisplayName())
                .tierRank(request.getTierRank() != null ? request.getTierRank() : 0)
                .active(Boolean.TRUE.equals(request.getActive()))
                .metadata(write(request.getMetadata()))
                .build());
        AdminBillingPlanResponse response = toPlanResponse(plan, List.of(), List.of());
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.BILLING_PLAN,
                response.getId(),
                null,
                response,
                Map.of("planCode", request.getPlanCode(), "tierCode", request.getTierCode())
        );
        return response;
    }

    @Transactional
    public AdminBillingPlanVersionResponse createPlanVersion(
            Long actorUserId,
            Long planId,
            CreateBillingPlanVersionRequest request
    ) {
        BillingPlan plan = billingPlanRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_BILLING_003, "Billing plan not found"));
        billingPlanVersionRepository.findOverlappingVersions(
                plan.getId(),
                toUtc(request.getEffectiveFrom()),
                toUtc(request.getEffectiveTo()),
                null
        ).stream().findAny().ifPresent(overlap -> {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_002, "Billing plan version overlaps with existing version");
        });

        BillingPlanVersion version = billingPlanVersionRepository.save(BillingPlanVersion.builder()
                .externalId(UUID.randomUUID())
                .billingPlanId(plan.getId())
                .versionNo(request.getVersionNo())
                .billingCycle(request.getBillingCycle())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .saleEnabled(Boolean.TRUE.equals(request.getSaleEnabled()))
                .renewalEnabled(Boolean.TRUE.equals(request.getRenewalEnabled()))
                .effectiveFrom(toUtc(request.getEffectiveFrom()))
                .effectiveTo(toUtc(request.getEffectiveTo()))
                .metadata(write(request.getMetadata()))
                .build());

        List<BillingPlanEntitlementItem> items = request.getEntitlementItems().stream()
                .map(item -> {
                    validateBucket(item.getBucketCode());
                    return entitlementItemRepository.save(BillingPlanEntitlementItem.builder()
                            .billingPlanVersionId(version.getId())
                            .bucketCode(item.getBucketCode())
                            .grantAmountMicros(item.getGrantAmountMicros())
                            .grantType(item.getGrantType())
                            .metadata(write(item.getMetadata()))
                            .build());
                })
                .toList();

        AdminBillingPlanVersionResponse response = toPlanVersionResponse(version, items);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.BILLING_PLAN_VERSION,
                response.getId(),
                null,
                response,
                Map.of("planId", planId, "versionNo", request.getVersionNo())
        );
        return response;
    }

    public List<AdminCreditPurchaseSkuResponse> getPurchaseSkus() {
        List<CreditPurchaseSkuVersion> versions = creditPurchaseSkuVersionRepository.findAll();
        return creditPurchaseSkuRepository.findAll().stream()
                .sorted(Comparator.comparing(CreditPurchaseSku::getSkuCode))
                .map(sku -> toPurchaseSkuResponse(sku, versions))
                .toList();
    }

    @Transactional
    public AdminCreditPurchaseSkuResponse createPurchaseSku(Long actorUserId, CreateCreditPurchaseSkuRequest request) {
        creditPurchaseSkuRepository.findBySkuCode(request.getSkuCode()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_002, "Credit purchase sku code already exists");
        });
        CreditPurchaseSku sku = creditPurchaseSkuRepository.save(CreditPurchaseSku.builder()
                .externalId(UUID.randomUUID())
                .skuCode(request.getSkuCode())
                .displayName(request.getDisplayName())
                .active(Boolean.TRUE.equals(request.getActive()))
                .metadata(write(request.getMetadata()))
                .build());
        CreateCreditPurchaseSkuRequest.VersionRequest versionRequest = request.getInitialVersion();
        CreditPurchaseSkuVersion version = creditPurchaseSkuVersionRepository.save(CreditPurchaseSkuVersion.builder()
                .externalId(UUID.randomUUID())
                .creditPurchaseSkuId(sku.getId())
                .versionNo(versionRequest.getVersionNo())
                .creditsAmountMicros(versionRequest.getCreditsAmountMicros())
                .amount(versionRequest.getAmount())
                .currency(versionRequest.getCurrency())
                .saleEnabled(Boolean.TRUE.equals(versionRequest.getSaleEnabled()))
                .effectiveFrom(toUtc(versionRequest.getEffectiveFrom()))
                .effectiveTo(toUtc(versionRequest.getEffectiveTo()))
                .metadata(write(versionRequest.getMetadata()))
                .build());
        AdminCreditPurchaseSkuResponse response = toPurchaseSkuResponse(sku, List.of(version));
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.CREDIT_PURCHASE_SKU,
                response.getId(),
                null,
                response,
                Map.of("skuCode", request.getSkuCode())
        );
        return response;
    }

    private AdminBillingPlanResponse toPlanResponse(
            BillingPlan plan,
            List<BillingPlanVersion> versions,
            List<BillingPlanEntitlementItem> entitlementItems
    ) {
        List<AdminBillingPlanVersionResponse> versionResponses = versions.stream()
                .filter(version -> version.getBillingPlanId().equals(plan.getId()))
                .sorted(Comparator.comparing(BillingPlanVersion::getVersionNo))
                .map(version -> toPlanVersionResponse(
                        version,
                        entitlementItems.stream()
                                .filter(item -> item.getBillingPlanVersionId().equals(version.getId()))
                                .toList()
                ))
                .toList();
        return AdminBillingPlanResponse.builder()
                .id(String.valueOf(plan.getId()))
                .planCode(plan.getPlanCode())
                .tierCode(plan.getTierCode())
                .displayName(plan.getDisplayName())
                .tierRank(plan.getTierRank())
                .active(plan.isActive())
                .metadata(read(plan.getMetadata()))
                .versions(versionResponses)
                .createdAt(toOffset(plan.getCreatedAt()))
                .updatedAt(toOffset(plan.getUpdatedAt()))
                .build();
    }

    private AdminBillingPlanVersionResponse toPlanVersionResponse(
            BillingPlanVersion version,
            List<BillingPlanEntitlementItem> items
    ) {
        return AdminBillingPlanVersionResponse.builder()
                .id(String.valueOf(version.getId()))
                .versionNo(version.getVersionNo())
                .billingCycle(version.getBillingCycle())
                .amount(creditFormattingService.formatAmount(version.getAmount()))
                .currency(version.getCurrency())
                .saleEnabled(version.isSaleEnabled())
                .renewalEnabled(version.isRenewalEnabled())
                .effectiveFrom(toOffset(version.getEffectiveFrom()))
                .effectiveTo(toOffset(version.getEffectiveTo()))
                .entitlementItems(items.stream()
                        .map(item -> AdminBillingPlanVersionResponse.EntitlementItem.builder()
                                .bucketCode(item.getBucketCode())
                                .grantType(item.getGrantType())
                                .grantAmountMicros(item.getGrantAmountMicros())
                                .grantAmount(creditFormattingService.formatCreditsMicros(item.getGrantAmountMicros()))
                                .metadata(read(item.getMetadata()))
                                .build())
                        .toList())
                .metadata(read(version.getMetadata()))
                .createdAt(toOffset(version.getCreatedAt()))
                .updatedAt(toOffset(version.getUpdatedAt()))
                .build();
    }

    private AdminCreditPurchaseSkuResponse toPurchaseSkuResponse(
            CreditPurchaseSku sku,
            List<CreditPurchaseSkuVersion> versions
    ) {
        return AdminCreditPurchaseSkuResponse.builder()
                .id(String.valueOf(sku.getId()))
                .skuCode(sku.getSkuCode())
                .displayName(sku.getDisplayName())
                .active(sku.isActive())
                .metadata(read(sku.getMetadata()))
                .versions(versions.stream()
                        .filter(version -> version.getCreditPurchaseSkuId().equals(sku.getId()))
                        .sorted(Comparator.comparing(CreditPurchaseSkuVersion::getVersionNo))
                        .map(version -> AdminCreditPurchaseSkuResponse.VersionItem.builder()
                                .id(String.valueOf(version.getId()))
                                .versionNo(version.getVersionNo())
                                .creditsAmountMicros(version.getCreditsAmountMicros())
                                .creditsAmount(creditFormattingService.formatCreditsMicros(version.getCreditsAmountMicros()))
                                .amount(creditFormattingService.formatAmount(version.getAmount()))
                                .currency(version.getCurrency())
                                .saleEnabled(version.isSaleEnabled())
                                .effectiveFrom(toOffset(version.getEffectiveFrom()))
                                .effectiveTo(toOffset(version.getEffectiveTo()))
                                .metadata(read(version.getMetadata()))
                                .createdAt(toOffset(version.getCreatedAt()))
                                .updatedAt(toOffset(version.getUpdatedAt()))
                                .build())
                        .toList())
                .createdAt(toOffset(sku.getCreatedAt()))
                .updatedAt(toOffset(sku.getUpdatedAt()))
                .build();
    }

    private OffsetDateTime toOffset(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private java.time.LocalDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private void validateBucket(String bucketCode) {
        try {
            ChargeBucket.valueOf(bucketCode);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001, "Unsupported billing bucket: " + bucketCode);
        }
    }

    private String write(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize billing catalog metadata", exception);
        }
    }

    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
