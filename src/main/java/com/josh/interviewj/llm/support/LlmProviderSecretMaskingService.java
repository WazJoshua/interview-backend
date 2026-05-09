package com.josh.interviewj.llm.support;

import org.springframework.stereotype.Component;

@Component
public class LlmProviderSecretMaskingService {

    public String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "****";
        }
        String normalized = secret.trim();
        if (normalized.length() <= 4) {
            return "****";
        }
        if (normalized.length() <= 8) {
            return normalized.substring(0, 1) + "****" + normalized.substring(normalized.length() - 1);
        }
        return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 2);
    }
}
