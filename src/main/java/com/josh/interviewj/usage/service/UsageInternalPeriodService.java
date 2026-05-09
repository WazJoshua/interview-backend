package com.josh.interviewj.usage.service;

import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmUsageInternalPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UsageInternalPeriodService {

    private final LlmUsageInternalPeriodRepository repository;

    public void increment(
            String provider,
            String modelCode,
            UsageFamily usageFamily,
            String purpose,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            long totalRecordedTokens,
            long totalRecordedCachedTokens,
            long totalRequestCount,
            long totalChargeableTokens,
            long totalChargeableRequestCount,
            BigDecimal totalBilledAmount,
            String currency
    ) {
        repository.upsertIncrement(
                "MONTHLY",
                periodStart,
                periodEnd,
                provider,
                modelCode,
                usageFamily.name(),
                purpose,
                totalRecordedTokens,
                totalRecordedCachedTokens,
                totalRequestCount,
                totalChargeableTokens,
                totalChargeableRequestCount,
                totalBilledAmount,
                currency
        );
    }
}
