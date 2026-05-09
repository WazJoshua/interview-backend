package com.josh.interviewj.service;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import com.josh.interviewj.auth.dto.response.PasswordKeyResponse;
import com.josh.interviewj.auth.service.PasswordKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordKeyServiceTest {

    private PasswordEncryptionProperties properties;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new PasswordEncryptionProperties();
        clock = Clock.fixed(Instant.parse("2026-03-29T10:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void getCurrentKey_UsesActiveKeyExpiryAndReturnsPublicPem() throws Exception {
        properties.setActiveKeyId("pwd-key-active");
        properties.setActivePrivateKeyPem(generatePrivateKeyPem());
        properties.setActiveKeyValidUntil(LocalDateTime.of(2026, 3, 30, 10, 0));
        properties.setPreviousKeyValidUntil(LocalDateTime.of(2026, 3, 29, 12, 0));

        PasswordKeyService service = new PasswordKeyService(properties, clock);

        PasswordKeyResponse response = service.getCurrentKey();

        assertEquals("pwd-key-active", response.getKeyId());
        assertEquals(LocalDateTime.of(2026, 3, 30, 10, 0), response.getExpiresAt());
        assertNotNull(response.getPublicKeyPem());
    }

    @Test
    void getCurrentKey_InvalidPrivateKey_ThrowsException() {
        properties.setActiveKeyId("pwd-key-active");
        properties.setActivePrivateKeyPem("invalid-private-key");
        properties.setActiveKeyValidUntil(LocalDateTime.of(2026, 3, 30, 10, 0));

        PasswordKeyService service = new PasswordKeyService(properties, clock);

        assertThrows(IllegalStateException.class, service::getCurrentKey);
    }

    private String generatePrivateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
