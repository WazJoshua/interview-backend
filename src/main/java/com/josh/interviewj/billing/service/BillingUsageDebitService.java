package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingUsageDebitService {

    private final BillingEventService billingEventService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final CreditWalletRepository creditWalletRepository;
    private final CreditLotRepository creditLotRepository;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    @Transactional
    public BillingUsageDebitResult debit(
            Long userId,
            String bucketCode,
            long requestedAmountMicros,
            LocalDateTime occurredAt,
            String usageEventSourceId
    ) {
        long remaining = requestedAmountMicros;
        long subscriptionAllocatedMicros = 0L;
        long purchasedAllocatedMicros = 0L;

        SubscriptionContract contract = subscriptionContractRepository.findOpenContractByUserId(userId).orElse(null);
        if (contract != null) {
            List<SubscriptionQuotaGrant> grants = subscriptionQuotaGrantRepository.findConsumableGrantsForUpdate(
                    contract.getId(),
                    bucketCode,
                    occurredAt
            );
            for (SubscriptionQuotaGrant grant : grants) {
                if (remaining <= 0) {
                    break;
                }
                long available = Math.max(grant.getGrantedAmountMicros() - grant.getUsedAmountMicros() - grant.getExpiredAmountMicros(), 0L);
                long allocated = Math.min(available, remaining);
                if (allocated <= 0) {
                    continue;
                }
                grant.setUsedAmountMicros(grant.getUsedAmountMicros() + allocated);
                remaining -= allocated;
                subscriptionAllocatedMicros += allocated;
            }
        }

        if (remaining > 0) {
            CreditWallet wallet = creditWalletRepository.findByUserId(userId).orElse(null);
            long availablePurchasedBalanceMicros = wallet == null ? 0L : Math.max(wallet.getPurchasedBalanceMicros(), 0L);
            long purchaseBudget = Math.min(remaining, availablePurchasedBalanceMicros);
            if (purchaseBudget > 0) {
                List<CreditLot> lots = creditLotRepository.findConsumableLotsForUpdate(
                        userId,
                        CreditLotStatus.ACTIVE,
                        occurredAt
                );
                long remainingPurchaseBudget = purchaseBudget;
                for (CreditLot lot : lots) {
                    if (remainingPurchaseBudget <= 0) {
                        break;
                    }
                    long allocated = Math.min(lot.getRemainingAmountMicros(), remainingPurchaseBudget);
                    if (allocated <= 0) {
                        continue;
                    }
                    lot.setRemainingAmountMicros(lot.getRemainingAmountMicros() - allocated);
                    if (lot.getRemainingAmountMicros() == 0L) {
                        lot.setStatus(CreditLotStatus.DEPLETED);
                    }
                    remainingPurchaseBudget -= allocated;
                    purchasedAllocatedMicros += allocated;
                    remaining -= allocated;
                }
                if (purchasedAllocatedMicros > 0) {
                    creditBalanceProjectionService.adjustWallet(userId, -purchasedAllocatedMicros);
                }
            }
        }

        if (remaining > 0) {
            throw new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subscriptionAllocatedMicros", subscriptionAllocatedMicros);
        metadata.put("purchasedAllocatedMicros", purchasedAllocatedMicros);
        metadata.put("requestedAmountMicros", requestedAmountMicros);
        BillingEvent billingEvent = billingEventService.createOrGet(
                userId,
                BillingEventType.USAGE_DEBIT,
                "LLM_USAGE_EVENT",
                usageEventSourceId,
                "usage-debit|" + usageEventSourceId + "|" + bucketCode,
                -requestedAmountMicros,
                bucketCode,
                occurredAt,
                metadata
        );
        return new BillingUsageDebitResult(
                billingEvent,
                subscriptionAllocatedMicros,
                purchasedAllocatedMicros
        );
    }
}
