package com.josh.interviewj.service;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import com.josh.interviewj.auth.support.PasswordEnvelopeDecryptor;
import com.josh.interviewj.auth.support.PasswordReplayGuard;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordEnvelopeDecryptorTest {

    @Mock
    private PasswordReplayGuard passwordReplayGuard;

    private PasswordEnvelopeDecryptor passwordEnvelopeDecryptor;
    private KeyPair keyPair;
    private Clock clock;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        keyPair = keyPairGenerator.generateKeyPair();
        clock = Clock.fixed(Instant.parse("2026-03-29T00:00:00Z"), ZoneOffset.UTC);

        PasswordEncryptionProperties properties = new PasswordEncryptionProperties();
        properties.setActiveKeyId("pwd-key-20260329");
        properties.setActivePrivateKeyPem(toPrivatePem());
        properties.setTimestampWindowMs(300_000L);
        properties.setNonceTtlMs(600_000L);

        passwordEnvelopeDecryptor = new PasswordEnvelopeDecryptor(properties, passwordReplayGuard, clock);
    }

    @Test
    void decrypt_ReturnsPlaintextForValidEnvelope() throws Exception {
        when(passwordReplayGuard.claim("pwd-key-20260329", "nonce-1")).thenReturn(true);

        String plaintext = passwordEnvelopeDecryptor.decrypt(
                envelope("nonce-1", clock.millis(), encrypt("Password123")),
                "passwordEnvelope",
                new HashSet<>());

        assertEquals("Password123", plaintext);
    }

    @Test
    void decrypt_ReturnsPlaintextForBase64UrlCiphertext() throws Exception {
        when(passwordReplayGuard.claim("pwd-key-20260329", "nonce-1")).thenReturn(true);

        String plaintext = passwordEnvelopeDecryptor.decrypt(
                envelope("nonce-1", clock.millis(), encryptBase64Url("Password123")),
                "passwordEnvelope",
                new HashSet<>());

        assertEquals("Password123", plaintext);
    }

    @Test
    void decrypt_RejectsTimestampOutsideWindow() throws Exception {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> passwordEnvelopeDecryptor.decrypt(
                        envelope("nonce-1", clock.millis() - 400_000L, encrypt("Password123")),
                        "passwordEnvelope",
                        new HashSet<>()));

        assertEquals("timestamp_out_of_window", exception.getDetails().getFirst().getCode());
    }

    @Test
    void decrypt_RejectsUnknownKeyId() throws Exception {
        PasswordEnvelope envelope = envelope("nonce-1", clock.millis(), encrypt("Password123"));
        envelope.setKeyId("unknown-key");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> passwordEnvelopeDecryptor.decrypt(envelope, "passwordEnvelope", new HashSet<>()));

        assertEquals("unknown_key_id", exception.getDetails().getFirst().getCode());
    }

    @Test
    void decrypt_RejectsDuplicateNonceInRequest() throws Exception {
        Set<String> requestNonces = new HashSet<>();
        requestNonces.add("nonce-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> passwordEnvelopeDecryptor.decrypt(
                        envelope("nonce-1", clock.millis(), encrypt("Password123")),
                        "newPasswordEnvelope",
                        requestNonces));

        assertEquals("duplicate_nonce_in_request", exception.getDetails().getFirst().getCode());
    }

    @Test
    void decrypt_RejectsReusedNonceFromReplayGuard() throws Exception {
        when(passwordReplayGuard.claim("pwd-key-20260329", "nonce-1")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> passwordEnvelopeDecryptor.decrypt(
                        envelope("nonce-1", clock.millis(), encrypt("Password123")),
                        "passwordEnvelope",
                        new HashSet<>()));

        assertEquals("nonce_reused", exception.getDetails().getFirst().getCode());
    }

    @Test
    void decrypt_RejectsInvalidCiphertext() {
        when(passwordReplayGuard.claim("pwd-key-20260329", "nonce-1")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> passwordEnvelopeDecryptor.decrypt(
                        envelope("nonce-1", clock.millis(), "not-base64"),
                        "passwordEnvelope",
                        new HashSet<>()));

        assertEquals("invalid_ciphertext", exception.getDetails().getFirst().getCode());
    }

    private PasswordEnvelope envelope(String nonce, long timestamp, String ciphertext) {
        PasswordEnvelope envelope = new PasswordEnvelope();
        envelope.setKeyId("pwd-key-20260329");
        envelope.setNonce(nonce);
        envelope.setTimestamp(timestamp);
        envelope.setCiphertext(ciphertext);
        return envelope;
    }

    private String encrypt(String plaintext) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSha256ParameterSpec());
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ciphertext);
    }

    private String encryptBase64Url(String plaintext) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSha256ParameterSpec());
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
    }

    private OAEPParameterSpec oaepSha256ParameterSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private String toPrivatePem() {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
