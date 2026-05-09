package com.josh.interviewj.llm.prompt.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Immutable revision history for LLM prompt templates.
 * Each revision cannot be edited after creation.
 */
@Entity
@Table(name = "llm_prompt_template_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmPromptTemplateRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "system_template")
    private String systemTemplate;

    @Column(name = "user_template")
    private String userTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    private String variables;

    @Column(name = "change_note")
    private String changeNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}