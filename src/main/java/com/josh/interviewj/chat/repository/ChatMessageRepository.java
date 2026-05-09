package com.josh.interviewj.chat.repository;

import com.josh.interviewj.chat.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides chat message lookups for session timelines and knowledge base stats aggregation.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop100ByChatSessionIdOrderBySequenceNumberDesc(Long sessionId);

    long countByChatSessionId(Long sessionId);

    Optional<ChatMessage> findByExternalId(UUID externalId);

    Optional<ChatMessage> findByChatSessionIdAndExternalId(Long sessionId, UUID externalId);

    @Query("select message from ChatMessage message where message.chatSessionId = :sessionId order by message.sequenceNumber desc")
    List<ChatMessage> findRecentByChatSessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    @Query("""
            select message
            from ChatMessage message
            where message.chatSessionId = :sessionId
              and message.sequenceNumber <= :sequenceNumber
            order by message.sequenceNumber desc
            """)
    List<ChatMessage> findWindowUpToSequenceNumber(
            @Param("sessionId") Long sessionId,
            @Param("sequenceNumber") Integer sequenceNumber,
            Pageable pageable
    );

    /**
     * Counts assistant answer messages under the given knowledge base session scope.
     *
     * @param kbExternalId knowledge base external id
     * @return number of assistant answers
     */
    @Query(
            value = """
                    SELECT COUNT(*)
                    FROM chat_messages message
                    JOIN chat_sessions session ON session.id = message.chat_session_id
                    WHERE session.domain_type = 'RAG_QA'
                      AND session.domain_ref_type = 'KNOWLEDGE_BASE'
                      AND session.domain_ref_external_id = :kbExternalId
                      AND message.role = 'ASSISTANT'
                      AND message.message_type = 'ANSWER'
                    """,
            nativeQuery = true
    )
    long countAssistantAnswersByKnowledgeBase(@Param("kbExternalId") UUID kbExternalId);

    /**
     * Calculates the average confidence from assistant answer metadata under the given knowledge base session scope.
     *
     * @param kbExternalId knowledge base external id
     * @return average confidence or {@code null} when no valid sample exists
     */
    @Query(
            value = """
                    SELECT AVG((message.metadata ->> 'confidence')::double precision)
                    FROM chat_messages message
                    JOIN chat_sessions session ON session.id = message.chat_session_id
                    WHERE session.domain_type = 'RAG_QA'
                      AND session.domain_ref_type = 'KNOWLEDGE_BASE'
                      AND session.domain_ref_external_id = :kbExternalId
                      AND message.role = 'ASSISTANT'
                      AND message.message_type = 'ANSWER'
                      AND message.metadata IS NOT NULL
                      AND jsonb_exists(message.metadata, 'confidence')
                      AND (message.metadata ->> 'confidence') ~ '^-?[0-9]+(\\.[0-9]+)?$'
                    """,
            nativeQuery = true
    )
    Double averageAssistantConfidenceByKnowledgeBase(@Param("kbExternalId") UUID kbExternalId);
}
