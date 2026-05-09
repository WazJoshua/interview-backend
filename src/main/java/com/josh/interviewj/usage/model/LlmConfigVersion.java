package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_config_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfigVersion {

    @Id
    @Column(name = "singleton_key", nullable = false, length = 32)
    private String singletonKey;

    @Column(name = "current_version", nullable = false)
    private Long currentVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
