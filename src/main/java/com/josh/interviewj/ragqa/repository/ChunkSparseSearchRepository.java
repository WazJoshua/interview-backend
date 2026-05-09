package com.josh.interviewj.ragqa.repository;

import com.josh.interviewj.knowledgebase.model.DocumentChunk;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChunkSparseSearchRepository extends Repository<DocumentChunk, Long> {

    @Query(
            value = """
                    SELECT
                        kd.external_id AS documentExternalId,
                        kd.file_name AS documentName,
                        dc.document_id AS documentId,
                        dc.chunk_index AS chunkIndex,
                        dc.content AS content,
                        dc.metadata AS metadata,
                        ts_rank_cd(
                            COALESCE(dc.sparse_content_tsv, to_tsvector('simple', '')),
                            plainto_tsquery('simple', :contentQuery)
                        ) AS contentRank,
                        ts_rank_cd(
                            COALESCE(dc.sparse_entities_tsv, to_tsvector('simple', '')),
                            plainto_tsquery('simple', :entityQuery)
                        ) AS entityRank,
                        CASE
                            WHEN EXISTS (
                                SELECT 1
                                FROM unnest(COALESCE(dc.sparse_exact_terms, ARRAY[]::text[])) AS stored_term(stored_term)
                                JOIN unnest(
                                    CASE
                                        WHEN :exactTermsPayload = '' THEN ARRAY[]::text[]
                                        ELSE string_to_array(:exactTermsPayload, chr(31))
                                    END
                                ) AS query_term(query_term)
                                  ON lower(stored_term) = lower(query_term)
                            )
                            THEN :exactBoostWeight
                            ELSE 0
                        END AS exactBoost,
                        (
                            :contentWeight * ts_rank_cd(
                                COALESCE(dc.sparse_content_tsv, to_tsvector('simple', '')),
                                plainto_tsquery('simple', :contentQuery)
                            )
                            + :entityWeight * ts_rank_cd(
                                COALESCE(dc.sparse_entities_tsv, to_tsvector('simple', '')),
                                plainto_tsquery('simple', :entityQuery)
                            )
                            + CASE
                                WHEN EXISTS (
                                    SELECT 1
                                    FROM unnest(COALESCE(dc.sparse_exact_terms, ARRAY[]::text[])) AS stored_term(stored_term)
                                    JOIN unnest(
                                        CASE
                                            WHEN :exactTermsPayload = '' THEN ARRAY[]::text[]
                                            ELSE string_to_array(:exactTermsPayload, chr(31))
                                        END
                                    ) AS query_term(query_term)
                                      ON lower(stored_term) = lower(query_term)
                                )
                                THEN :exactBoostWeight
                                ELSE 0
                            END
                        ) AS finalSparseScore
                    FROM document_chunks dc
                    JOIN kb_documents kd ON dc.document_id = kd.id
                    JOIN knowledge_bases kb ON kd.kb_id = kb.id
                    WHERE kb.external_id = :kbExternalId
                      AND kb.user_id = :userId
                      AND kd.status = 'COMPLETED'
                      AND kd.sparse_ready_version = :readyVersion
                      AND kd.sparse_ready_at IS NOT NULL
                      AND (
                          COALESCE(dc.sparse_content_tsv, to_tsvector('simple', '')) @@ plainto_tsquery('simple', :contentQuery)
                          OR COALESCE(dc.sparse_entities_tsv, to_tsvector('simple', '')) @@ plainto_tsquery('simple', :entityQuery)
                          OR EXISTS (
                              SELECT 1
                              FROM unnest(COALESCE(dc.sparse_exact_terms, ARRAY[]::text[])) AS stored_term(stored_term)
                              JOIN unnest(
                                  CASE
                                      WHEN :exactTermsPayload = '' THEN ARRAY[]::text[]
                                      ELSE string_to_array(:exactTermsPayload, chr(31))
                                  END
                              ) AS query_term(query_term)
                                ON lower(stored_term) = lower(query_term)
                          )
                      )
                    ORDER BY finalSparseScore DESC, dc.chunk_index ASC
                    LIMIT :topK
                    """,
            nativeQuery = true
    )
    List<SparseChunkProjection> searchCompletedChunksSparse(
            @Param("kbExternalId") UUID kbExternalId,
            @Param("userId") Long userId,
            @Param("contentQuery") String contentQuery,
            @Param("entityQuery") String entityQuery,
            @Param("exactTermsPayload") String exactTermsPayload,
            @Param("readyVersion") String readyVersion,
            @Param("contentWeight") double contentWeight,
            @Param("entityWeight") double entityWeight,
            @Param("exactBoostWeight") double exactBoostWeight,
            @Param("topK") int topK
    );

    interface SparseChunkProjection {
        UUID getDocumentExternalId();

        String getDocumentName();

        Long getDocumentId();

        Integer getChunkIndex();

        String getContent();

        String getMetadata();

        Double getContentRank();

        Double getEntityRank();

        Double getExactBoost();

        Double getFinalSparseScore();
    }
}
