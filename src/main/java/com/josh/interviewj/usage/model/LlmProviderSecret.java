package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_provider_secret")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private LlmProvider provider;

    @Column(name = "api_key_ciphertext", nullable = false, columnDefinition = "text")
    private String apiKeyCiphertext;

    @Column(name = "api_key_masked", nullable = false, length = 64)
    private String apiKeyMasked;

    @Column(name = "encryption_key_version", nullable = false, length = 64)
    private String encryptionKeyVersion;

    @Column(name = "encryption_type", nullable = false, length = 32)
    private String encryptionType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
