package com.josh.interviewj.interview.repository;

import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {

    List<InterviewQuestion> findBySessionIdOrderBySequenceNumberAsc(Long sessionId);

    List<InterviewQuestion> findBySessionIdAndQuestionKindOrderBySequenceNumberAsc(Long sessionId, InterviewQuestionKind questionKind);

    Optional<InterviewQuestion> findByExternalId(UUID externalId);

    Optional<InterviewQuestion> findBySessionIdAndExternalId(Long sessionId, UUID externalId);

    Optional<InterviewQuestion> findBySessionIdAndId(Long sessionId, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InterviewQuestion question
            SET question.sequenceNumber = question.sequenceNumber + 1
            WHERE question.sessionId = :sessionId
              AND question.sequenceNumber >= :fromSequenceNumber
            """)
    int incrementSequenceNumbersFrom(@Param("sessionId") Long sessionId, @Param("fromSequenceNumber") Integer fromSequenceNumber);
}
