package com.josh.interviewj.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Deprecated(forRemoval = true)
public class UsageFailureCompensationService {

    private final TransactionalUsageSettlementService transactionalUsageSettlementService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UsageOperationContext context) {
        if (context.businessOutcome() != UsageBusinessOutcome.FAILED_NON_CHARGEABLE
                && context.businessOutcome() != UsageBusinessOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE) {
            throw new IllegalArgumentException("Failure compensation requires a non-chargeable outcome");
        }
        transactionalUsageSettlementService.record(context);
    }
}
