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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_credit_policy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreditPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "resume_credits_limit_micros", nullable = false)
    private Long resumeCreditsLimitMicros;

    @Column(name = "kb_query_credits_limit_micros", nullable = false)
    private Long kbQueryCreditsLimitMicros;

    @Column(name = "kb_ingestion_credits_limit_micros", nullable = false)
    private Long kbIngestionCreditsLimitMicros;

    @Column(name = "interview_credits_limit_micros", nullable = false)
    private Long interviewCreditsLimitMicros;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
