package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmSecretProperties;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class LlmProviderSecretCryptoService {

    private static final String AES = "AES";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final LlmSecretProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedSecret encrypt(String plaintext) {
        String currentKeyVersion = properties.getCurrentKeyVersion();
        SecretKeySpec key = resolveKey(currentKeyVersion);
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return new EncryptedSecret(
                    Base64.getEncoder().encodeToString(payload),
                    currentKeyVersion,
                    properties.getEncryptionType()
            );
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt LLM provider secret", ex);
        }
    }

    public String decrypt(String ciphertext, String keyVersion) {
        SecretKeySpec key = resolveKey(keyVersion);
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("LLM provider secret payload is invalid");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_LENGTH_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, GCM_IV_LENGTH_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("LLM provider secret decryption failed", ex);
        }
    }

    public String decrypt(LlmProviderSecret secret) {
        return decrypt(secret.getApiKeyCiphertext(), secret.getEncryptionKeyVersion());
    }

    public EncryptedSecret rotate(String ciphertext, String keyVersion) {
        return encrypt(decrypt(ciphertext, keyVersion));
    }

    private SecretKeySpec resolveKey(String keyVersion) {
        String encodedKey = properties.getMasterKeys().get(keyVersion);
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("Unknown LLM master key version: " + keyVersion);
        }
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (!(decoded.length == 16 || decoded.length == 24 || decoded.length == 32)) {
            throw new IllegalArgumentException("LLM master key must be 128/192/256-bit AES material");
        }
        return new SecretKeySpec(decoded, AES);
    }

    public record EncryptedSecret(String ciphertext, String keyVersion, String encryptionType) {
    }
}
