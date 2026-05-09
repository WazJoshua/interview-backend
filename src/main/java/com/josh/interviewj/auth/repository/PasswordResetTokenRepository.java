package com.josh.interviewj.auth.repository;

import com.josh.interviewj.auth.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for password reset tokens.
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    @Query("""
            SELECT token
            FROM PasswordResetToken token
            WHERE token.tokenHash = :tokenHash
              AND token.usedAt IS NULL
              AND token.invalidatedAt IS NULL
              AND token.expiresAt > :now
            """)
    Optional<PasswordResetToken> findActiveByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE PasswordResetToken token
            SET token.invalidatedAt = :now
            WHERE token.userId = :userId
              AND token.usedAt IS NULL
              AND token.invalidatedAt IS NULL
              AND token.expiresAt > :now
            """)
    int invalidateActiveTokensByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
