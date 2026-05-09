package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.model.KbDocumentArtifact;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Accesses derived artifact content generated from knowledge base documents.
 */
@Repository
public interface KbDocumentArtifactRepository extends JpaRepository<KbDocumentArtifact, Long> {

    Optional<KbDocumentArtifact> findByDocumentIdAndArtifactType(Long documentId, KbDocumentArtifactType artifactType);

    @Transactional
    @Modifying
    @Query(
            value = """
                    INSERT INTO kb_document_artifacts (document_id, artifact_type, content, metadata)
                    VALUES (:documentId, :artifactType, :content, CAST(:metadata AS jsonb))
                    ON CONFLICT (document_id, artifact_type)
                    DO UPDATE SET
                        content = EXCLUDED.content,
                        metadata = EXCLUDED.metadata,
                        updated_at = CURRENT_TIMESTAMP
                    """,
            nativeQuery = true
    )
    int upsertArtifact(
            @Param("documentId") Long documentId,
            @Param("artifactType") String artifactType,
            @Param("content") String content,
            @Param("metadata") String metadata
    );

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM kb_document_artifacts WHERE document_id = :documentId", nativeQuery = true)
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Transactional
    @Modifying
    @Query(
            value = """
                    DELETE FROM kb_document_artifacts
                    WHERE document_id IN (
                        SELECT id FROM kb_documents WHERE kb_id = :kbId
                    )
                    """,
            nativeQuery = true
    )
    int deleteByKbId(@Param("kbId") Long kbId);
}
