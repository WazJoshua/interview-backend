package com.josh.interviewj.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Deprecated(forRemoval = true)
public class UsageRecordingFacade {

    private final TransactionalUsageSettlementService transactionalUsageSettlementService;

    public void record(UsageOperationContext context) {
        if (context.businessOutcome() != UsageBusinessOutcome.SUCCESS) {
            throw new IllegalArgumentException("UsageRecordingFacade only records successful outcomes");
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            transactionalUsageSettlementService.record(context);
            return;
        }

        transactionalUsageSettlementService.recordInNewTransaction(context);
    }
}
