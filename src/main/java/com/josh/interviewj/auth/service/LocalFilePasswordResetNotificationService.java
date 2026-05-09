package com.josh.interviewj.auth.service;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Writes reset notifications to build output for local verification.
 */
@RequiredArgsConstructor
public class LocalFilePasswordResetNotificationService implements PasswordResetNotificationService {

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Clock clock;

    @Override
    public void sendPasswordReset(String email, String rawToken, LocalDateTime expiresAt) {
        try {
            Path directory = Path.of("build", "password-reset-notifications");
            Files.createDirectories(directory);
            String fileName = LocalDateTime.now(clock).format(FILE_TIMESTAMP_FORMAT) + "-" + UUID.randomUUID() + ".txt";
            String content = "email=" + email + System.lineSeparator()
                    + "rawToken=" + rawToken + System.lineSeparator()
                    + "expiresAt=" + expiresAt + System.lineSeparator();
            Files.writeString(directory.resolve(fileName), content,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write password reset notification", ex);
        }
    }
}
