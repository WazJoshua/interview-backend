package com.josh.interviewj.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persistent analysis result for a resume evaluation run.
 */
@Entity
@Table(name = "resume_analysis_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeAnalysisReport {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<Map<String, Object>>> IMPROVEMENT_SUGGESTION_LIST_TYPE =
            new TypeReference<>() {
            };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "completeness_score", nullable = false)
    private Integer completenessScore;

    @Column(name = "clarity_score", nullable = false)
    private Integer clarityScore;

    @Column(name = "overall_score", nullable = false)
    private Integer overallScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvement_suggestions", columnDefinition = "jsonb")
    private String improvementSuggestionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_analysis", columnDefinition = "jsonb")
    private String sectionAnalysisJson;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "content_locale", length = 10)
    private String contentLocale;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Parses persisted improvement suggestions JSON into list form.
     *
     * @return improvement suggestions list or empty list when not available
     */
    public List<Map<String, Object>> getImprovementSuggestions() {
        if (improvementSuggestionsJson == null || improvementSuggestionsJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return OBJECT_MAPPER.readValue(improvementSuggestionsJson, IMPROVEMENT_SUGGESTION_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse improvement suggestions JSON", ex);
        }
    }

    /**
     * Serializes improvement suggestions into JSON for persistence.
     *
     * @param suggestions improvement suggestions
     */
    public void setImprovementSuggestions(List<Map<String, Object>> suggestions) {
        try {
            this.improvementSuggestionsJson = OBJECT_MAPPER.writeValueAsString(
                    suggestions == null ? Collections.emptyList() : suggestions
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize improvement suggestions", ex);
        }
    }
}
