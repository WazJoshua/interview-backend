package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.response.UserBillingEntitlementsResponse;
import com.josh.interviewj.billing.dto.response.UserBillingLedgerItemResponse;
import com.josh.interviewj.billing.dto.response.UserBillingSubscriptionResponse;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.service.CreditFormattingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingEntitlementQueryService {

    private final Clock clock;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final CreditFormattingService creditFormattingService;
    private final UserRepository userRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingEventRepository billingEventRepository;
    private final CreditWalletRepository creditWalletRepository;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    public UserBillingEntitlementsResponse getCurrentUserEntitlements(String username) {
        User user = requireUser(username);
        LocalDateTime now = nowUtc();
        SubscriptionContract contract = subscriptionContractRepository.findOpenContractByUserId(user.getId()).orElse(null);
        List<UserBillingEntitlementsResponse.Bucket> buckets = contract == null
                ? List.of()
                : subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(contract.getId()).stream()
                .filter(grant -> !grant.getPeriodStart().isAfter(now) && grant.getPeriodEnd().isAfter(now))
                .sorted(Comparator.comparing(SubscriptionQuotaGrant::getBucketCode))
                .map(grant -> {
                    long remainingMicros = Math.max(grant.getGrantedAmountMicros() - grant.getUsedAmountMicros() - grant.getExpiredAmountMicros(), 0L);
                    return UserBillingEntitlementsResponse.Bucket.builder()
                            .bucketCode(grant.getBucketCode())
                            .grantedAmountMicros(grant.getGrantedAmountMicros())
                            .usedAmountMicros(grant.getUsedAmountMicros())
                            .remainingAmountMicros(remainingMicros)
                            .grantedAmount(creditFormattingService.formatCreditsMicros(grant.getGrantedAmountMicros()))
                            .usedAmount(creditFormattingService.formatCreditsMicros(grant.getUsedAmountMicros()))
                            .remainingAmount(creditFormattingService.formatCreditsMicros(remainingMicros))
                            .build();
                })
                .toList();
        long purchasedBalanceMicros = creditWalletRepository.findByUserId(user.getId())
                .map(wallet -> wallet.getPurchasedBalanceMicros())
                .orElse(0L);
        long availablePurchasedBalanceMicros = Math.max(purchasedBalanceMicros, 0L);
        long displayTotalMicros = buckets.stream()
                .mapToLong(UserBillingEntitlementsResponse.Bucket::getRemainingAmountMicros)
                .sum() + availablePurchasedBalanceMicros;
        return UserBillingEntitlementsResponse.builder()
                .subscription(toSubscriptionSummary(contract))
                .subscriptionBuckets(buckets)
                .purchasedBalanceMicros(purchasedBalanceMicros)
                .availablePurchasedBalanceMicros(availablePurchasedBalanceMicros)
                .purchasedBalance(creditFormattingService.formatCreditsMicros(purchasedBalanceMicros))
                .availablePurchasedBalance(creditFormattingService.formatCreditsMicros(availablePurchasedBalanceMicros))
                .displayTotalCreditsMicros(displayTotalMicros)
                .displayTotalCredits(creditFormattingService.formatCreditsMicros(displayTotalMicros))
                .build();
    }

    public Page<UserBillingLedgerItemResponse> getCurrentUserLedger(String username, int page, int size) {
        User user = requireUser(username);
        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))
        );
        return billingEventRepository.findByUserId(user.getId(), pageRequest)
                .map(this::toLedgerItem);
    }

    public UserBillingSubscriptionResponse getCurrentUserSubscription(String username) {
        User user = requireUser(username);
        return toSubscriptionSummary(subscriptionContractRepository.findOpenContractByUserId(user.getId()).orElse(null));
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
    }

    private UserBillingLedgerItemResponse toLedgerItem(BillingEvent event) {
        return UserBillingLedgerItemResponse.builder()
                .eventType(event.getEventType().name())
                .sourceType(event.getSourceType())
                .sourceId(event.getSourceId())
                .deltaAmountMicros(event.getDeltaAmountMicros())
                .deltaAmount(creditFormattingService.formatCreditsMicros(event.getDeltaAmountMicros()))
                .bucketCode(event.getBucketCode())
                .occurredAt(toOffset(event.getOccurredAt()))
                .metadata(billingSnapshotCodec.readMap(event.getMetadata()))
                .build();
    }

    private UserBillingSubscriptionResponse toSubscriptionSummary(SubscriptionContract contract) {
        if (contract == null) {
            return null;
        }
        BillingPlan plan = billingPlanRepository.findById(contract.getBillingPlanId()).orElse(null);
        BillingPlanVersion version = billingPlanVersionRepository.findById(contract.getBillingPlanVersionId()).orElse(null);
        return UserBillingSubscriptionResponse.builder()
                .status(contract.getStatus().name())
                .planCode(plan == null ? null : plan.getPlanCode())
                .tierCode(plan == null ? null : plan.getTierCode())
                .currentBillingCycle(version == null ? null : version.getBillingCycle())
                .currentPeriodStart(toOffset(contract.getCurrentPeriodStart()))
                .currentPeriodEnd(toOffset(contract.getCurrentPeriodEnd()))
                .cancelAtPeriodEnd(contract.isCancelAtPeriodEnd())
                .graceUntil(toOffset(contract.getGraceUntil()))
                .provider(contract.getProvider())
                .autoRenew(contract.isAutoRenew())
                .build();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
