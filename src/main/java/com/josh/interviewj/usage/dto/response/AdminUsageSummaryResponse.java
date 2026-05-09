package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUsageSummaryResponse {

    private String dimension;
    private Overall overall;
    private List<Row> rows;

    @Data
    @Builder
    public static class Overall {
        private Long totalRecordedTokens;
        private Long totalRecordedCachedTokens;
        private Long totalRequestCount;
        private Long totalChargeableRequestCount;
        private Long pendingCostEventCount;
        private Long pendingCreditEventCount;
        private String totalBilledAmount;
        private String currency;
        private String totalChargedCredits;
        private Long totalChargedCreditsMicros;
    }

    @Data
    @Builder
    public static class Row {
        private String key;
        private String label;
        private String provider;
        private String modelCode;
        private String usageFamily;
        private String purpose;
        private Long totalRecordedTokens;
        private Long totalRecordedCachedTokens;
        private Long totalRequestCount;
        private Long totalChargeableRequestCount;
        private Long pendingCostEventCount;
        private Long pendingCreditEventCount;
        private String totalBilledAmount;
        private String currency;
        private String totalChargedCredits;
        private Long totalChargedCreditsMicros;
    }
}
