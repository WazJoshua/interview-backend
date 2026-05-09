package com.josh.interviewj.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "document_chunks",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_document_chunk_index", columnNames = {"document_id", "chunk_index"})
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "start_position")
    private Integer startPosition;

    @Column(name = "end_position")
    private Integer endPosition;

    @Column(name = "token_count")
    private Integer tokenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "sparse_content_tsv", columnDefinition = "tsvector")
    private String sparseContentTsv;

    @Column(name = "sparse_entities_tsv", columnDefinition = "tsvector")
    private String sparseEntitiesTsv;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sparse_exact_terms", columnDefinition = "text[]")
    private String[] sparseExactTerms;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Applies entity defaults before the chunk is first persisted.
     */
    @PrePersist
    public void prePersist() {
        if (chunkIndex == null) {
            chunkIndex = 0;
        }
    }
}
