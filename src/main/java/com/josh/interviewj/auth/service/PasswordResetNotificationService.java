package com.josh.interviewj.auth.service;

import java.time.LocalDateTime;

/**
 * Sends password reset notifications.
 */
public interface PasswordResetNotificationService {

    void sendPasswordReset(String email, String rawToken, LocalDateTime expiresAt);
}
