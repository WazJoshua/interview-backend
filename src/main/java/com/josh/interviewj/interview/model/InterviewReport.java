package com.josh.interviewj.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "interview_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private InterviewReportStatus status = InterviewReportStatus.NOT_READY;

    @Column(name = "summary_message_id")
    private UUID summaryMessageId;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "content_quality_score", precision = 5, scale = 2)
    private BigDecimal contentQualityScore;

    @Column(name = "expression_quality_score", precision = 5, scale = 2)
    private BigDecimal expressionQualityScore;

    @Column(name = "logic_quality_score", precision = 5, scale = 2)
    private BigDecimal logicQualityScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimension_scores", columnDefinition = "jsonb")
    private String dimensionScores;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "strengths", columnDefinition = "text[]")
    private String[] strengths;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "weaknesses", columnDefinition = "text[]")
    private String[] weaknesses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvement_suggestions", columnDefinition = "jsonb")
    private String improvementSuggestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_assessment", columnDefinition = "jsonb")
    private String skillAssessment;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (status == null) {
            status = InterviewReportStatus.NOT_READY;
        }
    }
}
