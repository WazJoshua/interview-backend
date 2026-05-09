package com.josh.interviewj.resume.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "resumes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", unique = true, nullable = false)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_url", nullable = false, length = 1000)
    private String fileUrl;

    @Column(name = "file_type", length = 255)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_content", columnDefinition = "jsonb")
    private String parsedContent;

    @Column(name = "target_job", length = 200)
    private String targetJob;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(length = 20, nullable = false)
    private ResumeStatus status = ResumeStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "analysis_status", length = 20, nullable = false)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Initialize default values before insert.
     */
    @PrePersist
    public void prePersist() {
        if (this.externalId == null) {
            this.externalId = UUID.randomUUID();
        }
        if (this.status == null) {
            this.status = ResumeStatus.UPLOADED;
        }
        if (this.analysisStatus == null) {
            this.analysisStatus = AnalysisStatus.PENDING;
        }
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
    }
}
