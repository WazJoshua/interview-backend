package com.josh.interviewj.llm.support;

import com.josh.interviewj.config.LlmSecretProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Small local-only helper for generating llm_provider_secret fields.
 *
 * Usage:
 *   ./gradlew testClasses
 *   java -cp build/classes/java/main:build/classes/java/test \
 *     com.josh.interviewj.llm.support.LlmProviderSecretEncryptorMain \
 *     --master-key-base64=... \
 *     --api-key=sk-xxx
 *
 * Or:
 *   LLM_MASTER_KEY_BASE64=... \
 *   java -cp build/classes/java/main:build/classes/java/test \
 *     com.josh.interviewj.llm.support.LlmProviderSecretEncryptorMain \
 *     --api-key=sk-xxx --api-key=sk-yyy
 */
public final class LlmProviderSecretEncryptorMain {

    private static final String DEFAULT_KEY_VERSION = "current";
    private static final String ENV_MASTER_KEY = "LLM_MASTER_KEY_BASE64";
    private static final String ENV_KEY_VERSION = "LLM_MASTER_KEY_VERSION";

    private LlmProviderSecretEncryptorMain() {
    }

    public static void main(String[] args) {
        Arguments parsed = Arguments.parse(args);
        if (parsed.apiKeys().isEmpty()) {
            printUsageAndExit("Missing --api-key");
        }

        String masterKeyBase64 = parsed.masterKeyBase64();
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            printUsageAndExit("Missing master key. Pass --master-key-base64 or set " + ENV_MASTER_KEY);
        }

        LlmSecretProperties properties = new LlmSecretProperties();
        properties.setCurrentKeyVersion(parsed.keyVersion());
        properties.getMasterKeys().put(parsed.keyVersion(), masterKeyBase64);

        LlmProviderSecretCryptoService cryptoService = new LlmProviderSecretCryptoService(properties);
        LlmProviderSecretMaskingService maskingService = new LlmProviderSecretMaskingService();

        for (String apiKey : parsed.apiKeys()) {
            LlmProviderSecretCryptoService.EncryptedSecret encrypted = cryptoService.encrypt(apiKey);
            System.out.println("apiKey=" + apiKey);
            System.out.println("apiKeyCiphertext=" + encrypted.ciphertext());
            System.out.println("apiKeyMasked=" + maskingService.mask(apiKey));
            System.out.println("encryptionKeyVersion=" + encrypted.keyVersion());
            System.out.println("encryptionType=" + encrypted.encryptionType());
            System.out.println("sqlValues=("
                    + quote(encrypted.ciphertext()) + ", "
                    + quote(maskingService.mask(apiKey)) + ", "
                    + quote(encrypted.keyVersion()) + ", "
                    + quote(encrypted.encryptionType()) + ")");
            System.out.println();
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void printUsageAndExit(String message) {
        System.err.println(message);
        System.err.println("Usage:");
        System.err.println("  java ... LlmProviderSecretEncryptorMain --master-key-base64=<base64> --api-key=<value>");
        System.err.println("  java ... LlmProviderSecretEncryptorMain --api-key=<value>");
        System.err.println("Environment:");
        System.err.println("  " + ENV_MASTER_KEY + "=<base64>");
        System.err.println("  " + ENV_KEY_VERSION + "=current");
        System.exit(1);
    }

    private record Arguments(String masterKeyBase64, String keyVersion, List<String> apiKeys) {

        private static Arguments parse(String[] args) {
            String masterKeyBase64 = System.getenv(ENV_MASTER_KEY);
            String keyVersion = defaultIfBlank(System.getenv(ENV_KEY_VERSION), DEFAULT_KEY_VERSION);
            List<String> apiKeys = new ArrayList<>();

            for (String arg : args) {
                if (arg.startsWith("--master-key-base64=")) {
                    masterKeyBase64 = arg.substring("--master-key-base64=".length());
                    continue;
                }
                if (arg.startsWith("--key-version=")) {
                    keyVersion = defaultIfBlank(arg.substring("--key-version=".length()), DEFAULT_KEY_VERSION);
                    continue;
                }
                if (arg.startsWith("--api-key=")) {
                    apiKeys.add(arg.substring("--api-key=".length()));
                    continue;
                }
                if (!arg.isBlank()) {
                    apiKeys.add(arg);
                }
            }

            return new Arguments(masterKeyBase64, keyVersion, List.copyOf(apiKeys));
        }

        private static String defaultIfBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
