package com.josh.interviewj.llm.prompt.repository;

import com.josh.interviewj.llm.prompt.model.LlmPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for LLM prompt template identity.
 */
@Repository
public interface LlmPromptTemplateRepository extends JpaRepository<LlmPromptTemplate, Long> {

    /**
     * Find template by unique key.
     *
     * @param templateKey the template key
     * @return template if found
     */
    Optional<LlmPromptTemplate> findByTemplateKey(String templateKey);

    /**
     * Find all templates by domain.
     *
     * @param domain the business domain
     * @return list of templates in the domain
     */
    List<LlmPromptTemplate> findByDomain(String domain);

    /**
     * Find all templates by domain and purpose.
     *
     * @param domain the business domain
     * @param purpose the purpose for filtering
     * @return list of templates matching domain and purpose
     */
    List<LlmPromptTemplate> findByDomainAndPurpose(String domain, String purpose);

    /**
     * Find all enabled templates.
     *
     * @return list of enabled templates
     */
    List<LlmPromptTemplate> findByEnabledTrue();

    /**
     * Check if template key exists.
     *
     * @param templateKey the template key
     * @return true if exists
     */
    boolean existsByTemplateKey(String templateKey);
}