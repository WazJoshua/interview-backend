package com.josh.interviewj.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOverviewResponse {

    private BigDecimal resumeAverageScore;
    private BigDecimal interviewAverageScore;
    private Long mockInterviewCompletedCount;
    private RecentActivity recentActivity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivity {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private LatestInterview latestInterview;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private LatestResume latestResume;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private LatestKnowledgeBaseQuestion latestKnowledgeBaseQuestion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatestInterview {
        private UUID interviewId;
        private String status;
        private String reportStatus;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private BigDecimal score;
        private LocalDateTime occurredAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatestResume {
        private UUID resumeId;
        private String fileName;
        private String uploadStatus;
        private LocalDateTime uploadedAt;
        private Boolean parsed;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private LocalDateTime parsedAt;
        private String analysisStatus;
        private Boolean analyzed;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private LocalDateTime analysisAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatestKnowledgeBaseQuestion {
        private UUID kbId;
        private String kbName;
        private String question;
        private LocalDateTime askedAt;
    }
}
