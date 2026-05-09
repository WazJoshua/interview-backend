package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class UserBillingEntitlementsResponse {

    private UserBillingSubscriptionResponse subscription;
    private List<Bucket> subscriptionBuckets;
    private Long purchasedBalanceMicros;
    private Long availablePurchasedBalanceMicros;
    private String purchasedBalance;
    private String availablePurchasedBalance;
    private String displayTotalCredits;
    private Long displayTotalCreditsMicros;

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
    }
}
