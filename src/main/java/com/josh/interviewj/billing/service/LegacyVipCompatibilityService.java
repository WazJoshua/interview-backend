package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LegacyVipCompatibilityService {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final SubscriptionContractRepository subscriptionContractRepository;

    public Set<String> appendLegacyVip(Long userId, Set<String> actualRoles) {
        Set<String> derivedRoles = new LinkedHashSet<>(actualRoles == null ? Set.of() : actualRoles);
        if (!billingProperties.getLegacyVip().isReadCompatEnabled()) {
            return derivedRoles;
        }
        SubscriptionContract contract = subscriptionContractRepository.findOpenContractByUserId(userId).orElse(null);
        if (contract == null) {
            return derivedRoles;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        boolean shouldExposeVip = contract.getStatus() == SubscriptionContractStatus.ACTIVE
                || (contract.getStatus() == SubscriptionContractStatus.PAST_DUE
                && contract.getGraceUntil() != null
                && contract.getGraceUntil().isAfter(now));
        if (shouldExposeVip) {
            derivedRoles.add("VIP");
        }
        return derivedRoles;
    }
}
