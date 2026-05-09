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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "kb_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_url", length = 1000)
    private String fileUrl;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Builder.Default
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "chunk_strategy", nullable = false, length = 30)
    private ChunkStrategy chunkStrategy = ChunkStrategy.FIXED_SIZE;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "previous_version")
    private Integer previousVersion;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private KbDocumentStatus status = KbDocumentStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Builder.Default
    @Column(name = "expected_chunk_count", nullable = false)
    private Integer expectedChunkCount = 0;

    @Builder.Default
    @Column(name = "embedded_chunk_count", nullable = false)
    private Integer embeddedChunkCount = 0;

    @Column(name = "sparse_ready_version", length = 32)
    private String sparseReadyVersion;

    @Column(name = "sparse_ready_at")
    private LocalDateTime sparseReadyAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /**
     * Applies entity defaults before the KB document is first persisted.
     */
    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
        if (chunkStrategy == null) {
            chunkStrategy = ChunkStrategy.FIXED_SIZE;
        }
        if (version == null) {
            version = 1;
        }
        if (status == null) {
            status = KbDocumentStatus.PENDING;
        }
        if (expectedChunkCount == null) {
            expectedChunkCount = 0;
        }
        if (embeddedChunkCount == null) {
            embeddedChunkCount = 0;
        }
    }
}
