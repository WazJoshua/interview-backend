package com.josh.interviewj.common.settings.model;

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
@Table(name = "system_setting_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSettingRevision {

    @Id
    @Column(name = "singleton_key", nullable = false, length = 32)
    private String singletonKey;

    @Column(name = "current_revision", nullable = false)
    private Long currentRevision;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
