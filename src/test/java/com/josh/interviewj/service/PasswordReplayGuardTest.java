package com.josh.interviewj.service;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import com.josh.interviewj.auth.support.PasswordReplayGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordReplayGuardTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PasswordReplayGuard passwordReplayGuard;

    @BeforeEach
    void setUp() {
        PasswordEncryptionProperties properties = new PasswordEncryptionProperties();
        properties.setNonceTtlMs(600_000L);
        passwordReplayGuard = new PasswordReplayGuard(stringRedisTemplate, properties);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void claim_ReturnsTrueWhenNonceIsFresh() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(Boolean.TRUE);

        boolean claimed = passwordReplayGuard.claim("active-key", "nonce-1");

        assertTrue(claimed);
        verify(valueOperations).setIfAbsent("password:nonce:active-key:nonce-1", "claimed", 600_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void claim_ReturnsFalseWhenNonceWasAlreadySeen() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(Boolean.FALSE);

        boolean claimed = passwordReplayGuard.claim("active-key", "nonce-1");

        assertFalse(claimed);
    }
}
