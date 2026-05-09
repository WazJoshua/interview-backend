package com.josh.interviewj.user.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface UserKnowledgeBaseOverviewReadRepository extends Repository<com.josh.interviewj.knowledgebase.model.KnowledgeBase, Long> {

    @Query(
            value = """
                    SELECT kb.external_id AS kbId,
                           kb.name AS kbName,
                           message.content AS question,
                           message.created_at AS askedAt
                    FROM chat_messages message
                    JOIN chat_sessions session ON session.id = message.chat_session_id
                    JOIN knowledge_bases kb ON kb.external_id = session.domain_ref_external_id
                    WHERE session.user_id = :targetUserId
                      AND session.domain_type = 'RAG_QA'
                      AND session.domain_ref_type = 'KNOWLEDGE_BASE'
                      AND kb.status <> 'DELETED'
                      AND message.role = 'USER'
                      AND message.message_type = 'TEXT'
                    ORDER BY message.created_at DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<LatestKnowledgeBaseQuestionProjection> findLatestQuestionByUserId(@Param("targetUserId") Long targetUserId);

    interface LatestKnowledgeBaseQuestionProjection {
        UUID getKbId();

        String getKbName();

        String getQuestion();

        LocalDateTime getAskedAt();
    }
}
