package com.josh.interviewj.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT utility for token generation, parsing, and validation.
 */
@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration:7200}")
    private Long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration:604800}")
    private Long refreshTokenExpiration;

    /**
     * Validates required JWT configuration at startup.
     */
    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must be configured via jwt.secret property or JWT_SECRET environment variable");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters long for HS256 algorithm");
        }
        log.info("JwtUtil initialized with access token expiration: {}s, refresh token expiration: {}s", 
                accessTokenExpiration, refreshTokenExpiration);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * Extracts username(subject) from token.
     *
     * @param token JWT token
     * @return subject username
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts token expiration timestamp.
     *
     * @param token JWT token
     * @return expiration date
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extracts a specific claim using resolver function.
     *
     * @param token JWT token
     * @param claimsResolver resolver function
     * @param <T> claim type
     * @return resolved claim value
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Generates an access token for the username.
     *
     * @param username username subject
     * @return signed access token
     */
    public String generateAccessToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        return createToken(claims, username, accessTokenExpiration);
    }

    /**
     * Generates a refresh token for the username.
     *
     * @param username username subject
     * @return signed refresh token
     */
    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return createToken(claims, username, refreshTokenExpiration);
    }

    private String createToken(Map<String, Object> claims, String subject, Long expirationTime) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expirationTime * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validates token signature and expiration.
     *
     * @param token JWT token
     * @return true when token is valid and not expired
     */
    public Boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates that token is a non-expired refresh token.
     *
     * @param token JWT token
     * @return true when token type is refresh and token is valid
     */
    public Boolean validateRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            String type = (String) claims.get("type");
            return "refresh".equals(type) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @return configured access token expiration in seconds
     */
    public Long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    /**
     * @return configured refresh token expiration in seconds
     */
    public Long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
}
