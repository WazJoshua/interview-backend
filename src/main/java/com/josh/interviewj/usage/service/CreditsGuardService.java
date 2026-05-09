package com.josh.interviewj.usage.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.model.UsageRejectionReasonCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreditsGuardService {

    private final Clock clock;
    private final CreditsBalanceSnapshotService creditsBalanceSnapshotService;
    private final UsageRejectionRecordingService usageRejectionRecordingService;

    public CreditsBalanceSnapshot requirePositiveSpendableCredits(
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId
    ) {
        return requirePositiveSpendableCredits(
                userId,
                chargeBucket,
                usageFamily,
                resourceType,
                resourceExternalId,
                operationId,
                null
        );
    }

    public CreditsBalanceSnapshot requirePositiveSpendableCredits(
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String businessOperationId
    ) {
        CreditsBalanceSnapshot snapshot = creditsBalanceSnapshotService.getSnapshot(userId);
        if (snapshot.spendableCreditsMicros() > 0L) {
            return snapshot;
        }
        usageRejectionRecordingService.recordPreflightRejected(
                userId,
                chargeBucket,
                usageFamily,
                resourceType,
                resourceExternalId,
                operationId,
                businessOperationId,
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                Map.of(
                        "errorCode", ErrorCode.USER_BILLING_001,
                        "spendableCreditsMicros", snapshot.spendableCreditsMicros(),
                        "totalCreditsMicros", snapshot.totalCreditsMicros()
                ),
                nowUtc()
        );
        throw new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance");
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
