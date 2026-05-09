package com.josh.interviewj.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UsageSuccessRecordingListener {

    private final TransactionalUsageSettlementService transactionalUsageSettlementService;

    public void onUsageSuccessRecorded(UsageSuccessRecordedEvent event) {
        transactionalUsageSettlementService.recordInNewTransaction(event.context());
    }
}
