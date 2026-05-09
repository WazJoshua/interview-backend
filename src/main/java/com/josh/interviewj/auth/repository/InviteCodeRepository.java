package com.josh.interviewj.auth.repository;

import com.josh.interviewj.auth.model.InviteCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inviteCode from InviteCode inviteCode where inviteCode.codeNormalized = :codeNormalized")
    Optional<InviteCode> findByCodeNormalizedForUpdate(@Param("codeNormalized") String codeNormalized);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Optional<InviteCode> findByExternalId(UUID externalId);

    @Override
    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByCreatedByUserId(Long createdByUserId, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByUsedAtIsNotNull(Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByUsedAtIsNullAndExpiresAtAfter(LocalDateTime now, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByUsedAtIsNullAndExpiresAtLessThanEqual(LocalDateTime now, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByCreatedByUserIdAndUsedAtIsNotNull(Long createdByUserId, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByCreatedByUserIdAndUsedAtIsNullAndExpiresAtAfter(
            Long createdByUserId,
            LocalDateTime now,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"createdByUser", "usedByUser"})
    Page<InviteCode> findByCreatedByUserIdAndUsedAtIsNullAndExpiresAtLessThanEqual(
            Long createdByUserId,
            LocalDateTime now,
            Pageable pageable
    );
}
