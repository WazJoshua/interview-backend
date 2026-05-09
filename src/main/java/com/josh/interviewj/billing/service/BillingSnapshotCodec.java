package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.CreditPurchaseSku;
import com.josh.interviewj.billing.model.CreditPurchaseSkuVersion;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class BillingSnapshotCodec {

    private final ObjectMapper objectMapper;

    public BillingSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writePlanPricingSnapshot(BillingPlan plan, BillingPlanVersion version) {
        return write(new PricingSnapshot(
                "SUBSCRIPTION",
                plan.getId(),
                version.getId(),
                plan.getPlanCode(),
                version.getBillingCycle(),
                null,
                null,
                version.getAmount(),
                version.getCurrency()
        ));
    }

    public String writePurchasePricingSnapshot(CreditPurchaseSku sku, CreditPurchaseSkuVersion version) {
        return write(new PricingSnapshot(
                "PURCHASE",
                null,
                null,
                null,
                null,
                sku.getId(),
                version.getId(),
                version.getAmount(),
                version.getCurrency(),
                sku.getSkuCode(),
                version.getCreditsAmountMicros()
        ));
    }

    public String writeEntitlementSnapshot(List<BillingPlanEntitlementItem> items) {
        return write(items.stream()
                .map(item -> new EntitlementSnapshotItem(
                        item.getBucketCode(),
                        item.getGrantAmountMicros(),
                        item.getGrantType(),
                        readMap(item.getMetadata())
                ))
                .toList());
    }

    public PricingSnapshot readPricingSnapshot(String rawSnapshot) {
        return read(rawSnapshot, PricingSnapshot.class);
    }

    public List<EntitlementSnapshotItem> readEntitlementSnapshot(String rawSnapshot) {
        return read(rawSnapshot, new TypeReference<List<EntitlementSnapshotItem>>() {
        });
    }

    public Map<String, Object> readMap(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Map.of();
        }
        return read(rawValue, new TypeReference<Map<String, Object>>() {
        });
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize billing snapshot", exception);
        }
    }

    private <T> T read(String rawValue, Class<T> type) {
        try {
            return objectMapper.readValue(rawValue, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize billing snapshot", exception);
        }
    }

    private <T> T read(String rawValue, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(rawValue, typeReference);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize billing snapshot", exception);
        }
    }
}

record PricingSnapshot(
        String snapshotType,
        Long billingPlanId,
        Long billingPlanVersionId,
        String planCode,
        String billingCycle,
        Long creditPurchaseSkuId,
        Long creditPurchaseSkuVersionId,
        BigDecimal amount,
        String currency,
        String skuCode,
        Long creditsAmountMicros
) {
    PricingSnapshot(
            String snapshotType,
            Long billingPlanId,
            Long billingPlanVersionId,
            String planCode,
            String billingCycle,
            Long creditPurchaseSkuId,
            Long creditPurchaseSkuVersionId,
            BigDecimal amount,
            String currency
    ) {
        this(snapshotType, billingPlanId, billingPlanVersionId, planCode, billingCycle, creditPurchaseSkuId, creditPurchaseSkuVersionId, amount, currency, null, null);
    }
}

record EntitlementSnapshotItem(
        String bucketCode,
        Long grantAmountMicros,
        String grantType,
        Map<String, Object> metadata
) {
}
