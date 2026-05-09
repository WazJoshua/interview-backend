package com.josh.interviewj.ragqa.repository;

import com.josh.interviewj.knowledgebase.model.DocumentChunk;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChunkSearchRepository extends Repository<DocumentChunk, Long> {

    @Query(
            value = """
                    SELECT
                        kd.external_id AS documentExternalId,
                        kd.file_name AS documentName,
                        dc.document_id AS documentId,
                        dc.chunk_index AS chunkIndex,
                        dc.content AS content,
                        dc.metadata AS metadata,
                        (1 - ((dc.embedding::halfvec(2048)) <=> CAST(:queryVector AS halfvec(2048)))) AS similarity
                    FROM document_chunks dc
                    JOIN kb_documents kd ON dc.document_id = kd.id
                    JOIN knowledge_bases kb ON kd.kb_id = kb.id
                    WHERE kb.external_id = :kbExternalId
                      AND kb.user_id = :userId
                      AND kd.status = 'COMPLETED'
                      AND dc.embedding IS NOT NULL
                    ORDER BY (dc.embedding::halfvec(2048)) <=> CAST(:queryVector AS halfvec(2048))
                    LIMIT :topK
                    """,
            nativeQuery = true
    )
    List<CompletedChunkProjection> searchCompletedChunks(
            @Param("kbExternalId") UUID kbExternalId,
            @Param("userId") Long userId,
            @Param("queryVector") String queryVector,
            @Param("topK") int topK
    );

    interface CompletedChunkProjection {
        UUID getDocumentExternalId();

        String getDocumentName();

        Long getDocumentId();

        Integer getChunkIndex();

        String getContent();

        String getMetadata();

        Double getSimilarity();
    }
}
