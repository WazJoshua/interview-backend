package com.josh.interviewj.ragqa.model;

public record DatabaseRerankConfig(
        String purpose,
        String providerKey,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutMs,
        int preRerankCandidateCap,
        int stage1TopN,
        double stage1RelevanceThreshold,
        boolean dualQueryEnabled
) {
}
