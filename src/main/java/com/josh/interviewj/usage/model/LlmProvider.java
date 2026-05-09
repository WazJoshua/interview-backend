package com.josh.interviewj.usage.model;

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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_provider")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class LlmProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_key", nullable = false, length = 100)
    private String providerKey;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "template_root", length = 255)
    private String templateRoot;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "default_timeout_ms")
    private Integer defaultTimeoutMs;

    @Column(name = "default_max_retries")
    private Integer defaultMaxRetries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_usage_families", columnDefinition = "jsonb")
    private String supportedUsageFamilies;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
