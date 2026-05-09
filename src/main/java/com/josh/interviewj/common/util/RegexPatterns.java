package com.josh.interviewj.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegexPatterns {

    /**
     * Utility class for common resume regex extraction.
     */
    private RegexPatterns() {
    }

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );

    // Focus on common CN mobile and simple international formats.
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?86[\\s-]?)?(?:1[3-9](?:[\\s-]?\\d){9})" +
                    "|" +
                    "(?:\\+\\d{1,3}(?:[\\s-]?\\d){6,14})"
    );

    private static final String DATE_SINGLE_PATTERN =
            "(?:" +
                    "(?:19|20)\\d{2}[./-](?:0?[1-9]|1[0-2])" +
                    "(?:[./-](?:0?[1-9]|[12]\\d|3[01]))?" +
                    "|" +
                    "(?:19|20)\\d{2}年(?:0?[1-9]|1[0-2])月" +
                    "(?:\\d{1,2}日)?" +
                    ")";

    private static final String DATE_END_PATTERN =
            "(?:" + DATE_SINGLE_PATTERN + "|至今|现在|present|Present)";

    // Covers: 2020-03, 2020/03, 2020.03, 2020年3月, and common ranges.
    // Range alternative is placed first to avoid splitting a range into two single matches.
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?:" +
                    DATE_SINGLE_PATTERN + "\\s*(?:-|~|至|–|—)\\s*" + DATE_END_PATTERN +
                    "|" +
                    DATE_SINGLE_PATTERN +
                    ")"
    );

    /**
     * Extract distinct email addresses from the given text.
     *
     * @param text input text
     * @return distinct emails (normalized to lower-case)
     */
    public static List<String> extractEmails(String text) {
        return extractDistinct(text, EMAIL_PATTERN, RegexPatterns::normalizeEmail);
    }

    /**
     * Extract distinct phone numbers from the given text.
     *
     * @param text input text
     * @return distinct phones (digits only, preserves leading '+')
     */
    public static List<String> extractPhones(String text) {
        return extractDistinct(text, PHONE_PATTERN, RegexPatterns::normalizePhone);
    }

    /**
     * Extract distinct date expressions / ranges from the given text.
     *
     * @param text input text
     * @return distinct date strings
     */
    public static List<String> extractDates(String text) {
        return extractDistinct(text, DATE_PATTERN, String::trim);
    }

    /**
     * Extract and normalize distinct matches from text.
     *
     * @param text input text
     * @param pattern regex pattern
     * @param normalizer normalization function
     * @return distinct normalized results
     */
    private static List<String> extractDistinct(String text, Pattern pattern, Normalizer normalizer) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Matcher matcher = pattern.matcher(text);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw == null) {
                continue;
            }
            String normalized = normalizer.normalize(raw);
            if (normalized != null && !normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Normalize email to a stable form.
     *
     * @param email raw email
     * @return normalized email
     */
    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize phone string by removing separators.
     *
     * @param phone raw phone
     * @return normalized phone
     */
    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Keep a leading '+' if present, remove other separators.
        boolean hasPlus = trimmed.startsWith("+");
        String digitsOnly = trimmed.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty()) {
            return null;
        }
        return hasPlus ? "+" + digitsOnly : digitsOnly;
    }

    @FunctionalInterface
    private interface Normalizer {

        /**
         * Normalize a raw regex match.
         *
         * @param raw raw match
         * @return normalized string
         */
        String normalize(String raw);
    }
}
