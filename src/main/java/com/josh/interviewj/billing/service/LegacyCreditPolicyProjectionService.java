package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.usage.model.UserCreditPolicy;
import com.josh.interviewj.usage.repository.UserCreditPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LegacyCreditPolicyProjectionService {

    private final Clock clock;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;
    private final UserCreditPolicyRepository userCreditPolicyRepository;

    @Transactional
    public void projectForUser(Long userId) {
        SubscriptionContract contract = subscriptionContractRepository.findOpenContractByUserId(userId).orElse(null);
        if (contract == null || contract.getCurrentPeriodStart() == null || contract.getCurrentPeriodEnd() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (contract.getCurrentPeriodEnd().isBefore(now)) {
            return;
        }
        List<SubscriptionQuotaGrant> activeGrants = subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(contract.getId()).stream()
                .filter(grant -> !grant.getPeriodStart().isAfter(now) && grant.getPeriodEnd().isAfter(now))
                .toList();
        if (activeGrants.isEmpty()) {
            return;
        }

        UserCreditPolicy existing = userCreditPolicyRepository.findActivePolicy(userId, now).orElse(null);
        if (existing != null
                && existing.getEffectiveFrom().equals(contract.getCurrentPeriodStart())
                && equalsOrNull(existing.getEffectiveTo(), contract.getCurrentPeriodEnd())) {
            existing.setResumeCreditsLimitMicros(limitForBucket(activeGrants, "RESUME_CREDITS"));
            existing.setKbQueryCreditsLimitMicros(limitForBucket(activeGrants, "KB_QUERY_CREDITS"));
            existing.setKbIngestionCreditsLimitMicros(limitForBucket(activeGrants, "KB_INGESTION_CREDITS"));
            existing.setInterviewCreditsLimitMicros(limitForBucket(activeGrants, "INTERVIEW_CREDITS"));
            userCreditPolicyRepository.save(existing);
            return;
        }

        userCreditPolicyRepository.findOverlappingPolicies(
                userId,
                contract.getCurrentPeriodStart(),
                contract.getCurrentPeriodEnd(),
                null
        ).forEach(policy -> {
            if (policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(contract.getCurrentPeriodStart())) {
                policy.setEffectiveTo(contract.getCurrentPeriodStart());
                userCreditPolicyRepository.save(policy);
            }
        });

        userCreditPolicyRepository.save(UserCreditPolicy.builder()
                .userId(userId)
                .effectiveFrom(contract.getCurrentPeriodStart())
                .effectiveTo(contract.getCurrentPeriodEnd())
                .resumeCreditsLimitMicros(limitForBucket(activeGrants, "RESUME_CREDITS"))
                .kbQueryCreditsLimitMicros(limitForBucket(activeGrants, "KB_QUERY_CREDITS"))
                .kbIngestionCreditsLimitMicros(limitForBucket(activeGrants, "KB_INGESTION_CREDITS"))
                .interviewCreditsLimitMicros(limitForBucket(activeGrants, "INTERVIEW_CREDITS"))
                .build());
    }

    private long limitForBucket(List<SubscriptionQuotaGrant> activeGrants, String bucketCode) {
        return activeGrants.stream()
                .filter(grant -> bucketCode.equals(grant.getBucketCode()))
                .mapToLong(SubscriptionQuotaGrant::getGrantedAmountMicros)
                .sum();
    }

    private boolean equalsOrNull(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right);
    }
}
