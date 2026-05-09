package com.josh.interviewj.support;

import com.josh.interviewj.auth.service.PasswordResetNotificationService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures password reset notifications for tests.
 */
public class InMemoryPasswordResetNotificationService implements PasswordResetNotificationService {

    private final List<NotificationRecord> notifications = new ArrayList<>();

    @Override
    public void sendPasswordReset(String email, String rawToken, LocalDateTime expiresAt) {
        notifications.add(new NotificationRecord(email, rawToken, expiresAt));
    }

    public void clear() {
        notifications.clear();
    }

    public NotificationRecord latest() {
        if (notifications.isEmpty()) {
            throw new IllegalStateException("No password reset notification captured");
        }
        return notifications.getLast();
    }

    public int size() {
        return notifications.size();
    }

    public record NotificationRecord(String email, String rawToken, LocalDateTime expiresAt) {
    }
}
