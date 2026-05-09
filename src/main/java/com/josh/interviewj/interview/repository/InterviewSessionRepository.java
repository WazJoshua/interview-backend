package com.josh.interviewj.interview.repository;

import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    Optional<InterviewSession> findByExternalId(UUID externalId);

    Optional<InterviewSession> findByChatSessionId(UUID chatSessionId);

    Optional<InterviewSession> findByExternalIdAndUserIdAndDeletedAtIsNull(UUID externalId, Long userId);

    Page<InterviewSession> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    Page<InterviewSession> findByUserIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(
            Long userId,
            InterviewStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session
            from InterviewSession session
            where session.externalId = :externalId
              and session.userId = :userId
              and session.deletedAt is null
            """)
    Optional<InterviewSession> findByExternalIdAndUserIdForUpdate(
            @Param("externalId") UUID externalId,
            @Param("userId") Long userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session
            from InterviewSession session
            where session.id = :id
            """)
    Optional<InterviewSession> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select session.id
            from InterviewSession session
            join ChatSession chat on chat.externalId = session.chatSessionId
            where session.status = com.josh.interviewj.interview.model.InterviewStatus.IN_PROGRESS
              and session.deletedAt is null
              and coalesce(chat.lastMessageAt, session.startTime, session.updatedAt) < :cutoff
            order by coalesce(chat.lastMessageAt, session.startTime, session.updatedAt) asc
            """)
    List<Long> findTimedOutInProgressSessionIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
