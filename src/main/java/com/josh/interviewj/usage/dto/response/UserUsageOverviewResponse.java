package com.josh.interviewj.usage.dto.response;

import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class UserUsageOverviewResponse {

    private OffsetDateTime snapshotAt;
    private DefaultWindow defaultWindow;
    private Balance balance;
    private Subscription subscription;

    @Data
    @Builder
    public static class DefaultWindow {
        private UsageHistoryWindowType windowType;
        private OffsetDateTime from;
        private OffsetDateTime to;
    }

    @Data
    @Builder
    public static class Balance {
        private Long totalCreditsMicros;
        private String totalCredits;
        private Long spendableCreditsMicros;
        private String spendableCredits;
        private Long purchasedAvailableCreditsMicros;
        private String purchasedAvailableCredits;
        private Long purchasedTotalCreditsMicros;
        private String purchasedTotalCredits;
        private Long purchasedUsedCreditsMicros;
        private String purchasedUsedCredits;
        private Long rawPurchasedBalanceMicros;
        private String rawPurchasedBalance;
        private Long subscriptionAvailableCreditsMicros;
        private String subscriptionAvailableCredits;
        private boolean hasAnyCredits;
        private boolean isNegative;
    }

    @Data
    @Builder
    public static class Subscription {
        private boolean active;
        private String status;
        private String planCode;
        private String tierCode;
        private String currentBillingCycle;
        private OffsetDateTime currentPeriodStart;
        private OffsetDateTime currentPeriodEnd;
        private int bucketCount;
        private List<Bucket> buckets;
    }

    @Data
    @Builder
    public static class Bucket {
        private String bucketCode;
        private Long grantedAmountMicros;
        private Long usedAmountMicros;
        private Long remainingAmountMicros;
        private String grantedAmount;
        private String usedAmount;
        private String remainingAmount;
        private OffsetDateTime periodStart;
        private OffsetDateTime periodEnd;
    }
}
