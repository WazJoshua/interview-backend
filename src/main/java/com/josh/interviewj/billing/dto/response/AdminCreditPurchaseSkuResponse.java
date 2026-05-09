package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AdminCreditPurchaseSkuResponse {

    private String id;
    private String skuCode;
    private String displayName;
    private boolean active;
    private Map<String, Object> metadata;
    private List<VersionItem> versions;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    public static class VersionItem {
        private String id;
        private Integer versionNo;
        private Long creditsAmountMicros;
        private String creditsAmount;
        private String amount;
        private String currency;
        private boolean saleEnabled;
        private OffsetDateTime effectiveFrom;
        private OffsetDateTime effectiveTo;
        private Map<String, Object> metadata;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }
}
