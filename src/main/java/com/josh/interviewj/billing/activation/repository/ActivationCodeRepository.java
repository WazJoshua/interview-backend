package com.josh.interviewj.billing.activation.repository;

import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivationCodeRepository extends JpaRepository<ActivationCode, Long>, JpaSpecificationExecutor<ActivationCode> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ActivationCode a WHERE a.code = :code")
    Optional<ActivationCode> findByCodeForUpdate(@Param("code") String code);

    Optional<ActivationCode> findByCode(String code);

    default Page<ActivationCode> findByFilters(
            ActivationCodeStatus status,
            ActivationCodeType codeType,
            UUID batchId,
            Long createdByUserId,
            LocalDateTime createdFrom,
            LocalDateTime createdTo,
            Pageable pageable) {
        Pageable effectivePageable = pageable;
        if (pageable.isPaged() && pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "createdAt")
            );
        }
        return findAll(withFilters(status, codeType, batchId, createdByUserId, createdFrom, createdTo), effectivePageable);
    }

    default List<ActivationCode> findAllByFilters(
            ActivationCodeStatus status,
            ActivationCodeType codeType,
            UUID batchId,
            Long createdByUserId,
            LocalDateTime createdFrom,
            LocalDateTime createdTo) {
        return findAll(
                withFilters(status, codeType, batchId, createdByUserId, createdFrom, createdTo),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
    }

    @Modifying
    @Query("""
            UPDATE ActivationCode a SET a.status = 'EXPIRED', a.updatedAt = :now
            WHERE a.status = 'UNUSED' AND a.expiresAt IS NOT NULL AND a.expiresAt < :now
            """)
    int expireOverdueCodes(@Param("now") LocalDateTime now);

    long countByStatus(ActivationCodeStatus status);

    List<ActivationCode> findByBatchId(UUID batchId);

    private static Specification<ActivationCode> withFilters(
            ActivationCodeStatus status,
            ActivationCodeType codeType,
            UUID batchId,
            Long createdByUserId,
            LocalDateTime createdFrom,
            LocalDateTime createdTo) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (codeType != null) {
                predicates.add(cb.equal(root.get("codeType"), codeType));
            }
            if (batchId != null) {
                predicates.add(cb.equal(root.get("batchId"), batchId));
            }
            if (createdByUserId != null) {
                predicates.add(cb.equal(root.get("createdByUserId"), createdByUserId));
            }
            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
