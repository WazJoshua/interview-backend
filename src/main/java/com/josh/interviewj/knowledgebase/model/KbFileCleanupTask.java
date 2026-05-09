package com.josh.interviewj.knowledgebase.model;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Persists deferred storage cleanup work for knowledge base files.
 */
@Entity
@Table(name = "kb_file_cleanup_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbFileCleanupTask {
    private static final long INITIAL_DRAIN_ELIGIBILITY_BUFFER_SECONDS = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_ref_id", nullable = false)
    private Long resourceRefId;

    @Column(name = "storage_key", nullable = false, length = 1000)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private KbFileCleanupTaskStatus status = KbFileCleanupTaskStatus.PENDING;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Applies default retry metadata before the task is first inserted.
     */
    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = KbFileCleanupTaskStatus.PENDING;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = LocalDateTime.now().minusSeconds(INITIAL_DRAIN_ELIGIBILITY_BUFFER_SECONDS);
        }
    }
}
