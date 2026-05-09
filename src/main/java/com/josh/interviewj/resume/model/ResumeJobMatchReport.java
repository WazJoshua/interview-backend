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

import java.time.LocalDateTime;

/**
 * Persistent job match report for resume × JD analysis.
 */
@Entity
@Table(name = "resume_job_match_reports")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeJobMatchReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "job_title", nullable = false, length = 200)
    private String jobTitle;

    @Column(name = "job_description", nullable = false, columnDefinition = "TEXT")
    private String jobDescription;

    @Column(name = "match_score")
    private Integer matchScore;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths", columnDefinition = "jsonb")
    private String strengthsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gaps", columnDefinition = "jsonb")
    private String gapsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions", columnDefinition = "jsonb")
    private String suggestionsJson;

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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
