package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Accesses persisted document chunks and chunk-level cleanup operations.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    long countByKbId(Long kbId);

    boolean existsByDocumentIdAndChunkIndex(Long documentId, Integer chunkIndex);

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Query(
            value = "SELECT chunk_index FROM document_chunks WHERE document_id = :documentId AND embedding IS NOT NULL ORDER BY chunk_index",
            nativeQuery = true
    )
    List<Integer> findEmbeddedChunkIndices(@Param("documentId") Long documentId);

    @Query(
            value = "SELECT COUNT(*) FROM document_chunks WHERE document_id = :documentId AND embedding IS NOT NULL",
            nativeQuery = true
    )
    int countEmbeddedChunks(@Param("documentId") Long documentId);

    @Query(value = "SELECT COUNT(*) FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    long countByDocumentId(@Param("documentId") Long documentId);

    @Transactional
    @Modifying
    @Query(
            value = "UPDATE document_chunks SET embedding = CAST(:embedding AS vector) " +
                    "WHERE document_id = :documentId AND chunk_index = :chunkIndex AND embedding IS NULL",
            nativeQuery = true
    )
    int updateEmbeddingIfNull(
            @Param("documentId") Long documentId,
            @Param("chunkIndex") Integer chunkIndex,
            @Param("embedding") String embedding
    );

    @Transactional
    @Modifying
    @Query(
            value = """
                    INSERT INTO document_chunks (
                        document_id,
                        kb_id,
                        content,
                        chunk_index,
                        start_position,
                        end_position,
                        token_count,
                        metadata,
                        sparse_content_tsv,
                        sparse_entities_tsv,
                        sparse_exact_terms
                    )
                    VALUES (
                        :documentId,
                        :kbId,
                        :content,
                        :chunkIndex,
                        :startPosition,
                        :endPosition,
                        :tokenCount,
                        CAST(:metadata AS jsonb),
                        to_tsvector('simple', :sparseContentText),
                        to_tsvector('simple', :sparseEntitiesText),
                        CASE
                            WHEN :sparseExactTermsPayload = '' THEN ARRAY[]::text[]
                            ELSE string_to_array(:sparseExactTermsPayload, chr(31))
                        END
                    )
                    ON CONFLICT (document_id, chunk_index)
                    DO UPDATE SET
                        content = EXCLUDED.content,
                        start_position = EXCLUDED.start_position,
                        end_position = EXCLUDED.end_position,
                        token_count = EXCLUDED.token_count,
                        metadata = EXCLUDED.metadata,
                        sparse_content_tsv = EXCLUDED.sparse_content_tsv,
                        sparse_entities_tsv = EXCLUDED.sparse_entities_tsv,
                        sparse_exact_terms = EXCLUDED.sparse_exact_terms
                    """,
            nativeQuery = true
    )
    int upsertChunk(
            @Param("documentId") Long documentId,
            @Param("kbId") Long kbId,
            @Param("content") String content,
            @Param("chunkIndex") Integer chunkIndex,
            @Param("startPosition") Integer startPosition,
            @Param("endPosition") Integer endPosition,
            @Param("tokenCount") Integer tokenCount,
            @Param("metadata") String metadata,
            @Param("sparseContentText") String sparseContentText,
            @Param("sparseEntitiesText") String sparseEntitiesText,
            @Param("sparseExactTermsPayload") String sparseExactTermsPayload
    );

    @Transactional
    @Modifying
    @Query(
            value = "DELETE FROM document_chunks WHERE document_id = :documentId AND chunk_index >= :chunkIndexThreshold",
            nativeQuery = true
    )
    int deleteByDocumentIdAndChunkIndexGreaterThanEqual(
            @Param("documentId") Long documentId,
            @Param("chunkIndexThreshold") Integer chunkIndexThreshold
    );

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_chunks WHERE document_id = :documentId", nativeQuery = true)
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_chunks WHERE kb_id = :kbId", nativeQuery = true)
    int deleteByKbId(@Param("kbId") Long kbId);
}
