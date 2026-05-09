package com.josh.interviewj.interview.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "interview_questions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_type", nullable = false, length = 30)
    private String questionType;

    @Column(name = "question_content", nullable = false, columnDefinition = "TEXT")
    private String questionContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_answer_points", columnDefinition = "jsonb")
    private String expectedAnswerPoints;

    @Builder.Default
    @Column(name = "difficulty", nullable = false)
    private Integer difficulty = 3;

    @Builder.Default
    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes = 3;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "question_kind", nullable = false, length = 20)
    private InterviewQuestionKind questionKind = InterviewQuestionKind.MAIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "follow_up_intent", length = 20)
    private InterviewFollowUpIntent followUpIntent;

    @Column(name = "parent_question_id")
    private Long parentQuestionId;

    @Builder.Default
    @Column(name = "branch_depth", nullable = false)
    private Integer branchDepth = 0;

    @Column(name = "prompt_message_id")
    private UUID promptMessageId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (difficulty == null) {
            difficulty = 3;
        }
        if (estimatedMinutes == null) {
            estimatedMinutes = 3;
        }
        if (questionKind == null) {
            questionKind = InterviewQuestionKind.MAIN;
        }
        if (branchDepth == null) {
            branchDepth = questionKind == InterviewQuestionKind.FOLLOW_UP ? 1 : 0;
        }
    }
}
