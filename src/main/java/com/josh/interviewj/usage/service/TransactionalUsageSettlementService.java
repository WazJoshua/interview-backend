package com.josh.interviewj.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionalUsageSettlementService {

    private final UsageSettlementService usageSettlementService;

    @Transactional
    public UsageSettlementResult record(UsageOperationContext context) {
        return usageSettlementService.settle(context);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageSettlementResult recordInNewTransaction(UsageOperationContext context) {
        return usageSettlementService.settle(context);
    }
}
