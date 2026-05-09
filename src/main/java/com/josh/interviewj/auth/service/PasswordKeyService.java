package com.josh.interviewj.auth.service;

import com.josh.interviewj.auth.config.PasswordEncryptionProperties;
import com.josh.interviewj.auth.dto.response.PasswordKeyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Exposes the active password encryption public key.
 */
@Service
@RequiredArgsConstructor
public class PasswordKeyService {

    private final PasswordEncryptionProperties properties;
    private final Clock clock;

    public PasswordKeyResponse getCurrentKey() {
        return PasswordKeyResponse.builder()
                .keyId(requireActiveKeyId())
                .algorithm("RSA-OAEP-256")
                .publicKeyPem(requirePublicKeyPem(properties.getActivePrivateKeyPem()))
                .issuedAt(LocalDateTime.now(clock))
                .expiresAt(properties.getActiveKeyValidUntil())
                .build();
    }

    private String requireActiveKeyId() {
        if (properties.getActiveKeyId() == null || properties.getActiveKeyId().isBlank()) {
            throw new IllegalStateException("Password encryption active keyId is not configured");
        }
        return properties.getActiveKeyId();
    }

    private String requirePublicKeyPem(String privateKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new IllegalStateException("Password encryption active private key is not configured");
        }
        try {
            String normalizedPem = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalizedPem);
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(publicKey.getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Password encryption active private key is invalid", ex);
        }
    }
}
