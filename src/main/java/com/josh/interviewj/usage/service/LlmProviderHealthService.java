package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.usage.dto.response.LlmHealthCheckResponse;
import com.josh.interviewj.usage.model.LlmConfigVersion;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.repository.LlmConfigVersionRepository;
import com.josh.interviewj.usage.repository.LlmProviderRepository;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LlmProviderHealthService {

    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderSecretRepository llmProviderSecretRepository;
    private final LlmRoutingPolicyRepository llmRoutingPolicyRepository;
    private final LlmConfigVersionRepository llmConfigVersionRepository;
    private final DatabaseLlmRouteSnapshotService snapshotService;
    private final LlmConfigCacheService llmConfigCacheService;

    public LlmHealthCheckResponse health() {
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotService.loadSnapshot();
        List<String> issues = new ArrayList<>(snapshot.invalidPurposes().values());
        issues.addAll(snapshot.invalidRerankPurposes().values());

        Long databaseVersion = llmConfigVersionRepository.findById(LlmConfigCacheService.GLOBAL_CONFIG_KEY)
                .map(LlmConfigVersion::getCurrentVersion)
                .orElse(0L);
        Long cachedVersion = llmConfigCacheService.cachedVersion();
        if (cachedVersion != null && !cachedVersion.equals(databaseVersion)) {
            issues.add("LLM config cache version is stale");
        }

        return LlmHealthCheckResponse.builder()
                .healthy(issues.isEmpty())
                .databaseVersion(databaseVersion)
                .cachedVersion(cachedVersion)
                .providerCount(llmProviderRepository.findAll().size())
                .routingCount(llmRoutingPolicyRepository.findAll().size())
                .secretKeyVersionStats(secretKeyVersionStats())
                .issues(issues)
                .build();
    }

    public Map<String, Long> secretKeyVersionStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (LlmProviderSecret secret : llmProviderSecretRepository.findAll()) {
            String keyVersion = secret.getEncryptionKeyVersion() == null ? "UNKNOWN" : secret.getEncryptionKeyVersion();
            stats.merge(keyVersion, 1L, Long::sum);
        }
        return stats;
    }

    public void invalidateLocalCache() {
        llmConfigCacheService.invalidate();
    }
}
