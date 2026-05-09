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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "chat_session_id", nullable = false, unique = true)
    private UUID chatSessionId;

    @Column(name = "job_title", length = 200)
    private String jobTitle;

    @Column(name = "job_description", columnDefinition = "TEXT")
    private String jobDescription;

    @Column(name = "content_locale", length = 10)
    private String contentLocale;

    @Builder.Default
    @Column(name = "difficulty_level", nullable = false, length = 20)
    private String difficultyLevel = "MID";

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "interview_mode", nullable = false, length = 20)
    private InterviewMode interviewMode = InterviewMode.TEXT;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "total_duration")
    private Integer totalDuration;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private InterviewStatus status = InterviewStatus.CREATED;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason", length = 32)
    private InterviewCompletionReason completionReason;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "current_question_id")
    private Long currentQuestionId;

    @Column(name = "main_question_count")
    private Integer mainQuestionCount;

    @Builder.Default
    @Column(name = "answered_main_question_count", nullable = false)
    private Integer answeredMainQuestionCount = 0;

    @Builder.Default
    @Column(name = "used_follow_up_count", nullable = false)
    private Integer usedFollowUpCount = 0;

    @Builder.Default
    @Column(name = "pending_follow_up_count", nullable = false)
    private Integer pendingFollowUpCount = 0;

    @Builder.Default
    @Column(name = "current_branch_depth", nullable = false)
    private Integer currentBranchDepth = 0;

    @Builder.Default
    @Column(name = "is_completable", nullable = false)
    private Boolean isCompletable = false;

    @Column(name = "running_score", precision = 5, scale = 2)
    private BigDecimal runningScore;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (difficultyLevel == null || difficultyLevel.isBlank()) {
            difficultyLevel = "MID";
        }
        if (interviewMode == null) {
            interviewMode = InterviewMode.TEXT;
        }
        if (status == null) {
            status = InterviewStatus.CREATED;
        }
        if (answeredMainQuestionCount == null) {
            answeredMainQuestionCount = 0;
        }
        if (usedFollowUpCount == null) {
            usedFollowUpCount = 0;
        }
        if (pendingFollowUpCount == null) {
            pendingFollowUpCount = 0;
        }
        if (currentBranchDepth == null) {
            currentBranchDepth = 0;
        }
        if (isCompletable == null) {
            isCompletable = false;
        }
        if (version == null) {
            version = 0;
        }
    }
}
