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
import java.util.UUID;

/**
 * Persists the aggregate state of one user-owned knowledge base.
 */
@Entity
@Table(name = "knowledge_bases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "embedding_model", nullable = false, length = 100)
    private String embeddingModel = "text-embedding-v4";

    @Builder.Default
    @Column(name = "vector_dimension", nullable = false)
    private Integer vectorDimension = 2048;

    @Builder.Default
    @Column(name = "document_count", nullable = false)
    private Integer documentCount = 0;

    @Builder.Default
    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks = 0;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Builder.Default
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "indexing_status", length = 32)
    private KnowledgeBaseIndexingStatus indexingStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Applies entity defaults before the knowledge base is first persisted.
     */
    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "text-embedding-v4";
        }
        if (vectorDimension == null) {
            vectorDimension = 2048;
        }
        if (documentCount == null) {
            documentCount = 0;
        }
        if (totalChunks == null) {
            totalChunks = 0;
        }
        if (version == null) {
            version = 1;
        }
        if (isPublic == null) {
            isPublic = false;
        }
        if (status == null) {
            status = KnowledgeBaseStatus.ACTIVE;
        }
    }
}
