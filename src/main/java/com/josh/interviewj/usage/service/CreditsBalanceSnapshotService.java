package com.josh.interviewj.usage.service;

import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.usage.repository.LlmUsageCreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CreditsBalanceSnapshotService {

    private final Clock clock;
    private final CreditWalletRepository creditWalletRepository;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;
    private final BillingEventRepository billingEventRepository;
    private final LlmUsageCreditLedgerRepository llmUsageCreditLedgerRepository;

    public CreditsBalanceSnapshot getSnapshot(Long userId) {
        LocalDateTime now = nowUtc();
        CreditWallet wallet = creditWalletRepository.findByUserId(userId).orElse(null);
        long rawPurchasedBalanceMicros = wallet == null ? 0L : wallet.getPurchasedBalanceMicros();
        long purchasedAvailableCreditsMicros = Math.max(rawPurchasedBalanceMicros, 0L);
        long purchasedTotalCreditsMicros = nullableToZero(billingEventRepository.sumNetPurchasedCreditsMicros(userId));
        long purchasedUsedCreditsMicros = nullableToZero(llmUsageCreditLedgerRepository.sumPurchasedAllocatedCreditsMicros(userId));

        SubscriptionContract openContract = subscriptionContractRepository.findOpenContractByUserId(userId).orElse(null);
        List<SubscriptionQuotaGrant> activeGrants = openContract == null
                ? List.of()
                : subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(openContract.getId()).stream()
                .filter(grant -> !grant.getPeriodStart().isAfter(now) && grant.getPeriodEnd().isAfter(now))
                .sorted(Comparator.comparing(SubscriptionQuotaGrant::getBucketCode)
                        .thenComparing(SubscriptionQuotaGrant::getPeriodEnd)
                        .thenComparing(SubscriptionQuotaGrant::getId))
                .toList();
        long subscriptionAvailableCreditsMicros = activeGrants.stream()
                .mapToLong(this::remainingAmountMicros)
                .sum();
        long totalCreditsMicros = subscriptionAvailableCreditsMicros + rawPurchasedBalanceMicros;
        long spendableCreditsMicros = Math.max(subscriptionAvailableCreditsMicros + purchasedAvailableCreditsMicros, 0L);
        boolean hasAnyCredits = wallet != null || !activeGrants.isEmpty() || openContract != null;

        return new CreditsBalanceSnapshot(
                rawPurchasedBalanceMicros,
                purchasedAvailableCreditsMicros,
                purchasedTotalCreditsMicros,
                purchasedUsedCreditsMicros,
                subscriptionAvailableCreditsMicros,
                totalCreditsMicros,
                spendableCreditsMicros,
                hasAnyCredits,
                totalCreditsMicros < 0L,
                openContract,
                activeGrants
        );
    }

    private long remainingAmountMicros(SubscriptionQuotaGrant grant) {
        return Math.max(grant.getGrantedAmountMicros() - grant.getUsedAmountMicros() - grant.getExpiredAmountMicros(), 0L);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private long nullableToZero(Long value) {
        return value == null ? 0L : value;
    }
}
