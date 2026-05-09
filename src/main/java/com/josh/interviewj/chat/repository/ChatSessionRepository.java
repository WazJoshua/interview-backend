package com.josh.interviewj.chat.repository;

import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByExternalId(UUID externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSession session where session.externalId = :externalId")
    Optional<ChatSession> findByExternalIdForUpdate(@Param("externalId") UUID externalId);

    Optional<ChatSession> findByExternalIdAndUserId(UUID externalId, Long userId);

    Optional<ChatSession> findByExternalIdAndUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalId(
            UUID externalId,
            Long userId,
            ChatDomainType domainType,
            ChatDomainRefType domainRefType,
            UUID domainRefExternalId
    );

    Page<ChatSession> findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdOrderByUpdatedAtDesc(
            Long userId,
            ChatDomainType domainType,
            ChatDomainRefType domainRefType,
            UUID domainRefExternalId,
            Pageable pageable
    );

    Page<ChatSession> findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdAndStatusOrderByUpdatedAtDesc(
            Long userId,
            ChatDomainType domainType,
            ChatDomainRefType domainRefType,
            UUID domainRefExternalId,
            ChatSessionStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSession session where session.id = :id")
    Optional<ChatSession> findByIdForUpdate(@Param("id") Long id);
}
