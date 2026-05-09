package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.usage.dto.response.LlmHealthCheckResponse;
import com.josh.interviewj.usage.model.LlmConfigVersion;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.repository.LlmConfigVersionRepository;
import com.josh.interviewj.usage.repository.LlmProviderRepository;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmProviderHealthServiceTest {

    @Test
    void health_WhenInvalidPurposesAndStaleCacheExist_ReturnsUnhealthyReport() {
        LlmProviderRepository providerRepository = mock(LlmProviderRepository.class);
        LlmProviderSecretRepository secretRepository = mock(LlmProviderSecretRepository.class);
        LlmRoutingPolicyRepository routingPolicyRepository = mock(LlmRoutingPolicyRepository.class);
        LlmConfigVersionRepository versionRepository = mock(LlmConfigVersionRepository.class);
        DatabaseLlmRouteSnapshotService snapshotService = mock(DatabaseLlmRouteSnapshotService.class);
        LlmConfigCacheService cacheService = mock(LlmConfigCacheService.class);

        when(providerRepository.findAll()).thenReturn(List.of(LlmProvider.builder().id(1L).providerKey("db-provider").build()));
        when(routingPolicyRepository.findAll()).thenReturn(List.of(LlmRoutingPolicy.builder().id(2L).purpose("analysis").build()));
        when(secretRepository.findAll()).thenReturn(List.of(
                LlmProviderSecret.builder().encryptionKeyVersion("current").build(),
                LlmProviderSecret.builder().encryptionKeyVersion("current").build()
        ));
        when(versionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(5L).build()));
        when(cacheService.cachedVersion()).thenReturn(4L);
        when(snapshotService.loadSnapshot()).thenReturn(new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of("analysis", "LLM routing model is missing for purpose: analysis"),
                Map.of(),
                Map.of("kb_query_rerank", "Rerank provider secret is invalid for purpose: kb_query_rerank")
        ));

        LlmProviderHealthService service = new LlmProviderHealthService(
                providerRepository,
                secretRepository,
                routingPolicyRepository,
                versionRepository,
                snapshotService,
                cacheService
        );

        LlmHealthCheckResponse response = service.health();

        assertThat(response.getHealthy()).isFalse();
        assertThat(response.getDatabaseVersion()).isEqualTo(5L);
        assertThat(response.getCachedVersion()).isEqualTo(4L);
        assertThat(response.getSecretKeyVersionStats()).containsEntry("current", 2L);
        assertThat(response.getIssues()).contains("LLM routing model is missing for purpose: analysis");
        assertThat(response.getIssues()).contains("Rerank provider secret is invalid for purpose: kb_query_rerank");
        assertThat(response.getIssues()).contains("LLM config cache version is stale");
    }
}
