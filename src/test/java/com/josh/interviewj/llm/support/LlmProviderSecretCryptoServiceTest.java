package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmSecretProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderSecretCryptoServiceTest {

    @Test
    void encryptAndDecrypt_WithCurrentKey_Succeeds() {
        LlmProviderSecretCryptoService cryptoService = new LlmProviderSecretCryptoService(properties());

        LlmProviderSecretCryptoService.EncryptedSecret encrypted = cryptoService.encrypt("sk-live-secret-value");

        assertThat(encrypted.ciphertext()).isNotBlank();
        assertThat(encrypted.keyVersion()).isEqualTo("current");
        assertThat(encrypted.encryptionType()).isEqualTo("AES_GCM");
        assertThat(cryptoService.decrypt(encrypted.ciphertext(), encrypted.keyVersion()))
                .isEqualTo("sk-live-secret-value");
    }

    @Test
    void decrypt_WhenKeyVersionUnknown_Throws() {
        LlmProviderSecretCryptoService cryptoService = new LlmProviderSecretCryptoService(properties());

        assertThatThrownBy(() -> cryptoService.decrypt("dGVzdA==", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown LLM master key version");
    }

    @Test
    void rotate_WhenDecryptingPreviousKey_ReEncryptsWithCurrentKey() {
        LlmSecretProperties previousOnlyProperties = new LlmSecretProperties();
        previousOnlyProperties.setCurrentKeyVersion("previous");
        previousOnlyProperties.getMasterKeys().put("previous", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        LlmProviderSecretCryptoService previousCryptoService = new LlmProviderSecretCryptoService(previousOnlyProperties);
        LlmProviderSecretCryptoService.EncryptedSecret previousEncrypted = previousCryptoService.encrypt("rotate-me");

        LlmProviderSecretCryptoService cryptoService = new LlmProviderSecretCryptoService(properties());

        LlmProviderSecretCryptoService.EncryptedSecret rotated = cryptoService.rotate(
                previousEncrypted.ciphertext(),
                previousEncrypted.keyVersion()
        );

        assertThat(rotated.keyVersion()).isEqualTo("current");
        assertThat(cryptoService.decrypt(rotated.ciphertext(), rotated.keyVersion())).isEqualTo("rotate-me");
    }

    @Test
    void mask_HidesLengthAndRemainsStable() {
        LlmProviderSecretMaskingService maskingService = new LlmProviderSecretMaskingService();

        assertThat(maskingService.mask("sk-live-secret-value")).isEqualTo("sk-****ue");
        assertThat(maskingService.mask("secret")).isEqualTo("s****t");
        assertThat(maskingService.mask("abc")).isEqualTo("****");
        assertThat(maskingService.mask("sk-live-secret-value")).isEqualTo("sk-****ue");
    }

    private LlmSecretProperties properties() {
        LlmSecretProperties properties = new LlmSecretProperties();
        properties.setCurrentKeyVersion("current");
        properties.getMasterKeys().put("current", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.getMasterKeys().put("previous", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        return properties;
    }
}
