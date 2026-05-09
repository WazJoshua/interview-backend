package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.service.SubscriptionRenewalOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRenewalPreparationJob {

    private final BillingProperties billingProperties;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionRenewalOrderService subscriptionRenewalOrderService;

    @Scheduled(fixedDelayString = "#{@billingProperties.subscription.renewalPreparation.pollInterval.toMillis()}")
    public void prepareRenewalOrders() {
        if (!billingProperties.getSubscription().getRenewalPreparation().isEnabled()) {
            return;
        }
        subscriptionContractRepository.findByStatusIn(List.of(
                        SubscriptionContractStatus.PENDING_ACTIVATION,
                        SubscriptionContractStatus.ACTIVE,
                        SubscriptionContractStatus.PAST_DUE
                ))
                .forEach(contract -> {
                    try {
                        subscriptionRenewalOrderService.ensureNextRenewalOrder(contract);
                    } catch (RuntimeException exception) {
                        log.warn("subscription_renewal_prepare_failed contractId={}, message={}", contract.getId(), exception.getMessage());
                    }
                });
    }
}
