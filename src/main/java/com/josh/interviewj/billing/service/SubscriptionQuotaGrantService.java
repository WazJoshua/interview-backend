package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionQuotaGrantService {

    private final BillingSnapshotCodec billingSnapshotCodec;
    private final SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    @Transactional
    public List<SubscriptionQuotaGrant> createGrants(
            Long subscriptionContractId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            BillingEvent sourceEvent,
            List<EntitlementSnapshotItem> entitlementItems
    ) {
        return entitlementItems.stream()
                .map(item -> subscriptionQuotaGrantRepository
                        .findBySubscriptionContractIdAndPeriodStartAndPeriodEndAndBucketCode(
                                subscriptionContractId,
                                periodStart,
                                periodEnd,
                                item.bucketCode()
                        )
                        .orElseGet(() -> subscriptionQuotaGrantRepository.save(SubscriptionQuotaGrant.builder()
                                .externalId(UUID.randomUUID())
                                .subscriptionContractId(subscriptionContractId)
                                .sourceBillingEventId(sourceEvent.getId())
                                .periodStart(periodStart)
                                .periodEnd(periodEnd)
                                .bucketCode(item.bucketCode())
                                .grantedAmountMicros(item.grantAmountMicros())
                                .usedAmountMicros(0L)
                                .expiredAmountMicros(0L)
                                .metadata(billingSnapshotCodec.write(item.metadata() == null ? Map.of() : item.metadata()))
                                .build())))
                .toList();
    }
}
