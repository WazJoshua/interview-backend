package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.ragqa.config.RerankProperties;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseRerankConfigResolverTest {

    @Test
    void resolve_WhenDatabaseRouteMissing_FallsBackToLegacyFileConfig() {
        RerankProperties rerankProperties = validLegacyProperties();
        DatabaseRerankConfigResolver resolver = new DatabaseRerankConfigResolver(emptySnapshotSupplier(), rerankProperties);

        DatabaseRerankConfig config = resolver.resolve("kb_query_rerank").orElseThrow();

        assertThat(config.providerKey()).isEqualTo("legacy-file-config");
        assertThat(config.baseUrl()).isEqualTo("https://legacy.example.com/v1/rerank");
        assertThat(config.apiKey()).isEqualTo("legacy-secret");
        assertThat(config.model()).isEqualTo("legacy-model");
        assertThat(config.timeoutMs()).isEqualTo(3200);
        assertThat(config.preRerankCandidateCap()).isEqualTo(24);
        assertThat(config.stage1TopN()).isEqualTo(10);
        assertThat(config.stage1RelevanceThreshold()).isEqualTo(0.15D);
        assertThat(config.dualQueryEnabled()).isTrue();
    }

    @Test
    void invalidReason_WhenLegacyFileConfigInvalid_ReturnsLegacyReason() {
        RerankProperties rerankProperties = validLegacyProperties();
        rerankProperties.setPreRerankCandidateCap(25);
        DatabaseRerankConfigResolver resolver = new DatabaseRerankConfigResolver(emptySnapshotSupplier(), rerankProperties);

        assertThat(resolver.resolve("kb_query_rerank")).isEmpty();
        assertThat(resolver.invalidReason("kb_query_rerank").orElse(""))
                .contains("Legacy rerank preRerankCandidateCap must be between 1 and 24");
    }

    @Test
    void resolve_WhenDatabaseRouteInvalid_DoesNotUseLegacyFallback() {
        RerankProperties rerankProperties = validLegacyProperties();
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot =
                new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("kb_query_rerank", "Rerank metadata missing for purpose: kb_query_rerank")
                );
        DatabaseRerankConfigResolver resolver = new DatabaseRerankConfigResolver(
                () -> snapshot,
                rerankProperties
        );

        assertThat(resolver.resolve("kb_query_rerank")).isEmpty();
        assertThat(resolver.invalidReason("kb_query_rerank").orElse(""))
                .contains("Rerank metadata missing for purpose: kb_query_rerank");
    }

    private java.util.function.Supplier<DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot> emptySnapshotSupplier() {
        return () -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private RerankProperties validLegacyProperties() {
        RerankProperties rerankProperties = new RerankProperties();
        rerankProperties.setEnabled(true);
        rerankProperties.setApiUrl("https://legacy.example.com/v1/rerank");
        rerankProperties.setApiKey("legacy-secret");
        rerankProperties.setModel("legacy-model");
        rerankProperties.setTimeoutMs(3200);
        rerankProperties.setPreRerankCandidateCap(24);
        rerankProperties.setStage1TopN(10);
        rerankProperties.setStage1RelevanceThreshold(0.15D);
        rerankProperties.setDualQueryEnabled(true);
        return rerankProperties;
    }
}
