package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resume analysis response payload returned to clients.
 */
@Data
@Builder
public class ResumeAnalysisResponseDTO {

    private Long reportId;

    private UUID resumeId;

    private AnalysisStatus status;

    private String contentLocale;

    private Scores scores;

    private String summary;

    private List<ImprovementSuggestion> improvementSuggestions;

    private List<SectionAnalysis> sectionAnalysis;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private String errorMessage;

    /**
     * Aggregated score dimensions produced by analysis.
     */
    @Data
    @Builder
    public static class Scores {
        private Integer completeness;
        private Integer clarity;
        private Integer overall;
    }

    /**
     * Improvement item with priority and example suggestion.
     */
    @Data
    @Builder
    public static class ImprovementSuggestion {
        private String category;
        private String priority;
        private String suggestion;
        private String example;
        private String section;
    }

    /**
     * Per-section quality analysis extracted from the scoring result.
     */
    @Data
    @Builder
    public static class SectionAnalysis {
        private String sectionName;
        private Integer score;
        private String feedback;
        private List<String> strengths;
        private List<String> weaknesses;
        private List<String> suggestions;
    }
}
