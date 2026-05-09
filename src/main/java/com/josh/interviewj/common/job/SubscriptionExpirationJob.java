package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "app.billing.subscription-expiration.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SubscriptionExpirationJob {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final SubscriptionContractRepository subscriptionContractRepository;

    @Scheduled(fixedDelayString = "#{@billingProperties.subscriptionExpiration.pollInterval.toMillis()}")
    @Transactional
    public void processExpiredContracts() {
        if (!billingProperties.getSubscriptionExpiration().isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int batchSize = billingProperties.getSubscriptionExpiration().getBatchSize();
        List<SubscriptionContract> expiredContracts = subscriptionContractRepository.findExpiredContracts(cutoff, batchSize);
        for (SubscriptionContract expiredContract : expiredContracts) {
            expiredContract.setStatus(SubscriptionContractStatus.EXPIRED);
            subscriptionContractRepository.save(expiredContract);
        }
        if (!expiredContracts.isEmpty()) {
            log.info("Subscription expiration job completed: expired {} contracts", expiredContracts.size());
        }
    }
}
