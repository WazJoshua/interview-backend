package com.josh.interviewj.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record InterviewReportResponse(
        UUID reportId,
        UUID interviewId,
        String reportStatus,
        String completionReason,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal overallScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal runningScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal contentQualityScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal expressionQualityScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal logicQualityScore,
        List<String> strengths,
        List<String> weaknesses,
        List<String> improvementSuggestions,
        LocalDateTime generatedAt,
        String failureCode,
        String failureMessage,
        Boolean retryable,
        LocalDateTime failedAt,
        String summary
) {
}