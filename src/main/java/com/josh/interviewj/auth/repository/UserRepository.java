package com.josh.interviewj.auth.repository;

import com.josh.interviewj.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for user persistence and authentication-related update operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    Optional<User> findByExternalId(UUID externalId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Increments failed login attempts for lockout policy.
     *
     * @param userId target user id
     */
    @Modifying
    @Query("UPDATE User u SET u.loginAttempts = u.loginAttempts + 1 WHERE u.id = ?1")
    void incrementFailedLoginAttempts(Long userId);

    /**
     * Clears failed login attempt counters after successful login.
     *
     * @param userId target user id
     */
    @Modifying
    @Query("UPDATE User u SET u.loginAttempts = 0, u.lockedUntil = null WHERE u.id = ?1")
    void resetFailedLoginAttempts(Long userId);

    /**
     * Sets account lock expiration timestamp.
     *
     * @param userId target user id
     * @param lockedUntil lock expiration time
     */
    @Modifying
    @Query("UPDATE User u SET u.lockedUntil = ?2 WHERE u.id = ?1")
    void lockUserAccount(Long userId, LocalDateTime lockedUntil);

    /**
     * Updates last successful login timestamp.
     *
     * @param userId target user id
     * @param lastLoginAt login timestamp
     */
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = ?2 WHERE u.id = ?1")
    void updateLastLoginAt(Long userId, LocalDateTime lastLoginAt);
}
