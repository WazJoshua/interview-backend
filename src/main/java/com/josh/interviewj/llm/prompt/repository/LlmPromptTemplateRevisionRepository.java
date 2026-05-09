package com.josh.interviewj.llm.prompt.repository;

import com.josh.interviewj.llm.prompt.model.LlmPromptTemplateRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for immutable LLM prompt template revisions.
 */
@Repository
public interface LlmPromptTemplateRevisionRepository extends JpaRepository<LlmPromptTemplateRevision, Long> {

    /**
     * Find revision by template ID and revision number.
     *
     * @param templateId the template ID
     * @param revisionNo the revision number
     * @return revision if found
     */
    Optional<LlmPromptTemplateRevision> findByTemplateIdAndRevisionNo(Long templateId, Integer revisionNo);

    /**
     * Find all revisions for a template, ordered by revision number descending.
     *
     * @param templateId the template ID
     * @return list of revisions
     */
    List<LlmPromptTemplateRevision> findByTemplateIdOrderByRevisionNoDesc(Long templateId);

    /**
     * Find the maximum revision number for a template.
     *
     * @param templateId the template ID
     * @return maximum revision number, or null if no revisions exist
     */
    @Query("SELECT MAX(r.revisionNo) FROM LlmPromptTemplateRevision r WHERE r.templateId = :templateId")
    Optional<Integer> findMaxRevisionNo(@Param("templateId") Long templateId);

    /**
     * Count revisions for a template.
     *
     * @param templateId the template ID
     * @return count of revisions
     */
    long countByTemplateId(Long templateId);
}