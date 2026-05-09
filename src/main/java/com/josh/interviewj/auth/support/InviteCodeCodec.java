package com.josh.interviewj.auth.support;

import com.josh.interviewj.auth.model.InviteCodeStatus;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class InviteCodeCodec {

    public static final int CODE_LENGTH = 12;
    public static final String DISPLAY_PATTERN = "XXXX-XXXX-XXXX";
    public static final boolean CASE_SENSITIVE = false;
    public static final boolean ALLOW_WHITESPACE = true;
    public static final boolean ALLOW_HYPHEN = true;
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final Set<Character> ALLOWED_CHARACTERS = ALPHABET.chars()
            .mapToObj(value -> (char) value)
            .collect(Collectors.toUnmodifiableSet());

    private final SecureRandom secureRandom = new SecureRandom();

    public String normalize(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        return rawCode.trim()
                .replace(" ", "")
                .replace("-", "")
                .toUpperCase(Locale.ROOT);
    }

    public boolean isCanonicalFormat(String normalizedCode) {
        if (normalizedCode == null || normalizedCode.length() != CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < normalizedCode.length(); index++) {
            if (!ALLOWED_CHARACTERS.contains(normalizedCode.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    public String formatForDisplay(String normalizedCode) {
        if (!isCanonicalFormat(normalizedCode)) {
            throw new IllegalArgumentException("Invite code must be canonical before formatting");
        }
        return normalizedCode.substring(0, 4)
                + "-"
                + normalizedCode.substring(4, 8)
                + "-"
                + normalizedCode.substring(8, 12);
    }

    public InviteCodeStatus calculateStatus(
            LocalDateTime usedAt,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        if (usedAt != null) {
            return InviteCodeStatus.USED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return InviteCodeStatus.EXPIRED;
        }
        return InviteCodeStatus.UNUSED;
    }

    public String generateCanonicalCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
