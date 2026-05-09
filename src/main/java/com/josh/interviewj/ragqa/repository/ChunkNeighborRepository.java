package com.josh.interviewj.ragqa.repository;

import com.josh.interviewj.knowledgebase.model.DocumentChunk;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkNeighborRepository extends Repository<DocumentChunk, Long> {

    @Query(
            value = """
                    SELECT
                        dc.chunk_index AS chunkIndex,
                        dc.content AS content,
                        dc.metadata AS metadata,
                        dc.token_count AS tokenCount
                    FROM document_chunks dc
                    WHERE dc.document_id = :documentId
                    ORDER BY dc.chunk_index
                    """,
            nativeQuery = true
    )
    List<DocumentChunkSliceProjection> findDocumentChunks(@Param("documentId") Long documentId);

    @Query(
            value = """
                    SELECT
                        dc.chunk_index AS chunkIndex,
                        dc.content AS content,
                        dc.metadata AS metadata,
                        dc.token_count AS tokenCount
                    FROM document_chunks dc
                    WHERE dc.document_id = :documentId
                      AND dc.chunk_index BETWEEN :fromIndex AND :toIndex
                    ORDER BY dc.chunk_index
                    """,
            nativeQuery = true
    )
    List<NeighborChunkProjection> findNeighborChunks(
            @Param("documentId") Long documentId,
            @Param("fromIndex") int fromIndex,
            @Param("toIndex") int toIndex
    );

    interface DocumentChunkSliceProjection {
        Integer getChunkIndex();

        String getContent();

        String getMetadata();

        Integer getTokenCount();
    }

    interface NeighborChunkProjection {
        Integer getChunkIndex();

        String getContent();

        String getMetadata();

        Integer getTokenCount();
    }
}
