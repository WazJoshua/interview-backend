package com.josh.interviewj.chat.model;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain_type", nullable = false, length = 32)
    private ChatDomainType domainType;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain_ref_type", nullable = false, length = 32)
    private ChatDomainRefType domainRefType;

    @Column(name = "domain_ref_external_id", nullable = false)
    private UUID domainRefExternalId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private ChatSessionStatus status = ChatSessionStatus.ACTIVE;

    @Builder.Default
    @Column(name = "title", nullable = false, length = 200)
    private String title = "";

    @Builder.Default
    @Column(name = "next_message_sequence", nullable = false)
    private Integer nextMessageSequence = 1;

    @Builder.Default
    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;

    @Column(name = "last_message_preview", length = 500)
    private String lastMessagePreview;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (status == null) {
            status = ChatSessionStatus.ACTIVE;
        }
        if (title == null) {
            title = "";
        }
        if (nextMessageSequence == null) {
            nextMessageSequence = 1;
        }
        if (messageCount == null) {
            messageCount = 0;
        }
    }
}
