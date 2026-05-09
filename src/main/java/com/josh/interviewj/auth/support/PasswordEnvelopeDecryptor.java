package com.josh.interviewj.auth.support;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Validates and decrypts password envelopes.
 */
@Component
@RequiredArgsConstructor
public class PasswordEnvelopeDecryptor {

    private final PasswordEncryptionProperties properties;
    private final PasswordReplayGuard passwordReplayGuard;
    private final Clock clock;

    public String decrypt(PasswordEnvelope envelope, String fieldName, Set<String> requestNonces) {
        if (envelope == null) {
            throw protocolException(fieldName, "invalid_ciphertext", "Password envelope is required");
        }
        validateTimestamp(envelope, fieldName);

        PrivateKey privateKey = resolvePrivateKey(envelope.getKeyId(), fieldName);
        if (!requestNonces.add(envelope.getNonce())) {
            throw protocolException(fieldName + ".nonce", "duplicate_nonce_in_request",
                    "Nonce must be unique within the same request");
        }
        if (!passwordReplayGuard.claim(envelope.getKeyId(), envelope.getNonce())) {
            throw protocolException(fieldName + ".nonce", "nonce_reused", "Password nonce has already been used");
        }

        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSha256ParameterSpec());
            byte[] decodedCiphertext = decodeCiphertext(envelope.getCiphertext());
            byte[] plaintext = cipher.doFinal(decodedCiphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw protocolException(fieldName + ".ciphertext", "invalid_ciphertext",
                    "Password ciphertext is invalid");
        }
    }

    private void validateTimestamp(PasswordEnvelope envelope, String fieldName) {
        long now = clock.millis();
        long skew = Math.abs(envelope.getTimestamp() - now);
        if (skew <= properties.getTimestampWindowMs()) {
            return;
        }
        throw protocolException(fieldName + ".timestamp", "timestamp_out_of_window",
                "Client clock skew " + skew + "ms exceeds allowed window " + properties.getTimestampWindowMs() + "ms");
    }

    private PrivateKey resolvePrivateKey(String keyId, String fieldName) {
        if (keyId != null && keyId.equals(properties.getActiveKeyId())) {
            return parsePrivateKey(properties.getActivePrivateKeyPem(), fieldName);
        }
        if (keyId != null
                && keyId.equals(properties.getPreviousKeyId())
                && properties.getPreviousPrivateKeyPem() != null
                && properties.getPreviousKeyValidUntil() != null
                && properties.getPreviousKeyValidUntil().isAfter(LocalDateTime.now(clock))) {
            return parsePrivateKey(properties.getPreviousPrivateKeyPem(), fieldName);
        }
        throw protocolException(fieldName + ".keyId", "unknown_key_id", "Password key is invalid or expired");
    }

    private PrivateKey parsePrivateKey(String pem, String fieldName) {
        if (pem == null || pem.isBlank()) {
            throw protocolException(fieldName + ".keyId", "unknown_key_id", "Password key is invalid or expired");
        }
        try {
            String normalizedPem = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalizedPem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw protocolException(fieldName + ".keyId", "unknown_key_id", "Password key is invalid or expired");
        }
    }

    private byte[] decodeCiphertext(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Password ciphertext must not be blank");
        }

        String normalizedCiphertext = ciphertext.replace('-', '+').replace('_', '/');
        int padding = normalizedCiphertext.length() % 4;
        if (padding != 0) {
            normalizedCiphertext = normalizedCiphertext + "=".repeat(4 - padding);
        }
        return Base64.getDecoder().decode(normalizedCiphertext);
    }

    private OAEPParameterSpec oaepSha256ParameterSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private BusinessException protocolException(String fieldName, String detailCode, String message) {
        ErrorResponse.ErrorDetails.ErrorDetail detail = ErrorResponse.ErrorDetails.ErrorDetail.builder()
                .field(fieldName)
                .code(detailCode)
                .message(message)
                .build();
        return new BusinessException("AUTH_007", "Password encryption payload is invalid", List.of(detail));
    }
}
