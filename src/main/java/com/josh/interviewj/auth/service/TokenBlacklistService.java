package com.josh.interviewj.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;

    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    private static final String USER_TOKENS_PREFIX = "user:tokens:";

    @Value("${jwt.access-token-expiration:7200}")
    private Long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration:604800}")
    private Long refreshTokenExpiration;

    public void addToBlacklist(String token, long expirationSeconds) {
        String key = BLACKLIST_PREFIX + token;
        redisTemplate.opsForValue().set(key, "revoked", expirationSeconds, TimeUnit.SECONDS);
        log.debug("Token added to blacklist, expires in {}s", expirationSeconds);
    }

    public boolean isBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void invalidateAllUserTokens(String username) {
        String userTokensKey = USER_TOKENS_PREFIX + username;
        var tokens = redisTemplate.opsForSet().members(userTokensKey);
        
        if (tokens != null && !tokens.isEmpty()) {
            for (String token : tokens) {
                addToBlacklist(token, accessTokenExpiration);
            }
            redisTemplate.delete(userTokensKey);
            log.info("Invalidated {} tokens for user: {}", tokens.size(), username);
        }
    }

    public void registerUserToken(String username, String token) {
        String userTokensKey = USER_TOKENS_PREFIX + username;
        redisTemplate.opsForSet().add(userTokensKey, token);
        redisTemplate.expire(userTokensKey, refreshTokenExpiration, TimeUnit.SECONDS);
    }
}
