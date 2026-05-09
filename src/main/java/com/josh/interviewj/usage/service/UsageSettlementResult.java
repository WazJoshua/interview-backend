package com.josh.interviewj.usage.service;

import com.josh.interviewj.usage.model.LlmUsageChargeLedger;
import com.josh.interviewj.usage.model.LlmUsageCreditLedger;
import com.josh.interviewj.usage.model.LlmUsageEvent;

public record UsageSettlementResult(
        boolean duplicateIgnored,
        LlmUsageEvent usageEvent,
        LlmUsageChargeLedger chargeLedger,
        LlmUsageCreditLedger creditLedger
) {

    public static UsageSettlementResult ignoredDuplicate() {
        return new UsageSettlementResult(true, null, null, null);
    }
}
