package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.InviteCodeStatus;
import com.josh.interviewj.auth.support.InviteCodeCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteCodeCodecTest {

    private InviteCodeCodec codec;

    @BeforeEach
    void setUp() {
        codec = new InviteCodeCodec();
    }

    @Test
    void normalize_RemovesWhitespaceHyphenAndUppercases() {
        assertEquals("ABCDWXYZ2345", codec.normalize("  abcd- wxyz -2345  "));
    }

    @Test
    void normalize_BlankInputBecomesEmptyString() {
        assertEquals("", codec.normalize("  - -   "));
        assertEquals("", codec.normalize(null));
    }

    @Test
    void isCanonicalFormat_RejectsIllegalCharacters() {
        assertFalse(codec.isCanonicalFormat("ABCDWXYZ23I5"));
        assertFalse(codec.isCanonicalFormat("ABCDWXYZ2305"));
        assertFalse(codec.isCanonicalFormat("SHORT"));
    }

    @Test
    void formatForDisplay_InsertsHyphenEveryFourChars() {
        assertEquals("ABCD-WXYZ-2345", codec.formatForDisplay("ABCDWXYZ2345"));
    }

    @Test
    void calculateStatus_ReturnsUnusedWhenNotUsedAndNotExpired() {
        InviteCodeStatus status = codec.calculateStatus(
                null,
                LocalDateTime.of(2026, 3, 27, 0, 0),
                LocalDateTime.of(2026, 3, 26, 0, 0)
        );

        assertEquals(InviteCodeStatus.UNUSED, status);
    }

    @Test
    void calculateStatus_ReturnsUsedWhenUsedAtExists() {
        InviteCodeStatus status = codec.calculateStatus(
                LocalDateTime.of(2026, 3, 26, 8, 0),
                LocalDateTime.of(2026, 3, 27, 0, 0),
                LocalDateTime.of(2026, 3, 26, 9, 0)
        );

        assertEquals(InviteCodeStatus.USED, status);
    }

    @Test
    void calculateStatus_ReturnsExpiredWhenUnusedAndExpired() {
        InviteCodeStatus status = codec.calculateStatus(
                null,
                LocalDateTime.of(2026, 3, 25, 23, 59),
                LocalDateTime.of(2026, 3, 26, 0, 0)
        );

        assertEquals(InviteCodeStatus.EXPIRED, status);
    }

    @Test
    void generateCanonicalCode_UsesAllowedAlphabetAndFixedLength() {
        String generated = codec.generateCanonicalCode();

        assertEquals(InviteCodeCodec.CODE_LENGTH, generated.length());
        assertTrue(codec.isCanonicalFormat(generated));
    }
}
