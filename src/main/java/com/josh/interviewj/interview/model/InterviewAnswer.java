package com.josh.interviewj.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "interview_answers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_session_question", columnNames = {"session_id", "question_id"})
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "answer_content", columnDefinition = "TEXT")
    private String answerContent;

    @Column(name = "answer_audio_url", length = 500)
    private String answerAudioUrl;

    @Column(name = "evaluation_score", precision = 5, scale = 2)
    private BigDecimal evaluationScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_details", columnDefinition = "jsonb")
    private String evaluationDetails;

    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "user_message_id")
    private UUID userMessageId;

    @Column(name = "evaluation_message_id")
    private UUID evaluationMessageId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
    }
}
