package com.josh.interviewj.usage.dto.response;

import com.josh.interviewj.usage.model.UsageEntryType;
import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistorySourceType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class UserUsageHistoryItemResponse {

    private String id;
    private UsageEntryType entryType;
    private UsageHistoryCategory category;
    private String categoryLabel;
    private OffsetDateTime occurredAt;
    private Long creditsDeltaMicros;
    private String creditsDelta;
    private UsageHistorySourceType sourceType;
    private SourceBreakdown sourceBreakdown;
    private UsageDetails usage;
    private GrantDetails grant;
    private AdjustmentDetails adjustment;
    private RejectionDetails rejection;

    @Data
    @Builder
    public static class SourceBreakdown {
        private Long subscriptionAllocatedMicros;
        private String subscriptionAllocated;
        private Long purchasedAllocatedMicros;
        private String purchasedAllocated;
    }

    @Data
    @Builder
    public static class UsageDetails {
        private String usageFamily;
        private String chargeBucket;
        private String resourceType;
        private String resourceExternalId;
        private String operationId;
    }

    @Data
    @Builder
    public static class GrantDetails {
        private String eventType;
        private String sourceType;
        private String sourceId;
        private String bucketCode;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    public static class AdjustmentDetails {
        private String eventType;
        private String sourceType;
        private String sourceId;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    public static class RejectionDetails {
        private String reasonCode;
        private String reasonMessage;
        private Map<String, Object> metadata;
    }
}
