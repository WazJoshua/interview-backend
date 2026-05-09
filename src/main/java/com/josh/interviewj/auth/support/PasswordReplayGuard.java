package com.josh.interviewj.auth.support;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Prevents replay of password envelopes via Redis one-time nonce claims.
 */
@Component
@RequiredArgsConstructor
public class PasswordReplayGuard {

    private final StringRedisTemplate stringRedisTemplate;
    private final PasswordEncryptionProperties properties;

    public boolean claim(String keyId, String nonce) {
        String redisKey = "password:nonce:" + keyId + ":" + nonce;
        Boolean claimed = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "claimed", properties.getNonceTtlMs(), TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(claimed);
    }
}
