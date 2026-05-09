package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AdminUserCreditPolicyResponse {

    private String userId;
    private Policy activePolicy;
    private Policy pendingPolicy;
    private CurrentPeriod currentPeriod;

    @Data
    @Builder
    public static class Policy {
        private String id;
        private OffsetDateTime effectiveFrom;
        private OffsetDateTime effectiveTo;
        private String resumeCreditsLimit;
        private String kbQueryCreditsLimit;
        private String kbIngestionCreditsLimit;
        private String interviewCreditsLimit;
        private Long resumeCreditsLimitMicros;
        private Long kbQueryCreditsLimitMicros;
        private Long kbIngestionCreditsLimitMicros;
        private Long interviewCreditsLimitMicros;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    @Builder
    public static class CurrentPeriod {
        private String periodType;
        private OffsetDateTime periodStart;
        private OffsetDateTime periodEnd;
        private String resumeCreditsUsed;
        private String kbQueryCreditsUsed;
        private String kbIngestionCreditsUsed;
        private String interviewCreditsUsed;
        private Long resumeCreditsUsedMicros;
        private Long kbQueryCreditsUsedMicros;
        private Long kbIngestionCreditsUsedMicros;
        private Long interviewCreditsUsedMicros;
    }
}
