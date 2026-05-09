package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AdminBillingPlanVersionResponse {

    private String id;
    private Integer versionNo;
    private String billingCycle;
    private String amount;
    private String currency;
    private boolean saleEnabled;
    private boolean renewalEnabled;
    private OffsetDateTime effectiveFrom;
    private OffsetDateTime effectiveTo;
    private List<EntitlementItem> entitlementItems;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    public static class EntitlementItem {
        private String bucketCode;
        private String grantType;
        private Long grantAmountMicros;
        private String grantAmount;
        private Map<String, Object> metadata;
    }
}
