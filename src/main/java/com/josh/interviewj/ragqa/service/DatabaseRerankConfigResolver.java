package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.ragqa.config.RerankProperties;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

@Component
public class DatabaseRerankConfigResolver {

    private static final int MAX_PRE_RERANK_CANDIDATE_CAP = 24;
    private static final String PURPOSE_KB_QUERY_RERANK = "kb_query_rerank";
    private static final String LEGACY_FILE_CONFIG_PROVIDER_KEY = "legacy-file-config";

    private final Supplier<DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot> snapshotSupplier;
    private final Supplier<LegacyRerankResolution> legacyResolutionSupplier;

    @Autowired
    public DatabaseRerankConfigResolver(
            LlmConfigCacheService llmConfigCacheService,
            RerankProperties rerankProperties
    ) {
        this(llmConfigCacheService::getSnapshot, () -> resolveLegacyRerank(rerankProperties));
    }

    public DatabaseRerankConfigResolver(Supplier<DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot> snapshotSupplier) {
        this(snapshotSupplier, LegacyRerankResolution::empty);
    }

    DatabaseRerankConfigResolver(
            Supplier<DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot> snapshotSupplier,
            RerankProperties rerankProperties
    ) {
        this(snapshotSupplier, () -> resolveLegacyRerank(rerankProperties));
    }

    public DatabaseRerankConfigResolver(
            Supplier<DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot> snapshotSupplier,
            Supplier<LegacyRerankResolution> legacyResolutionSupplier
    ) {
        this.snapshotSupplier = snapshotSupplier;
        this.legacyResolutionSupplier = legacyResolutionSupplier;
    }

    public Optional<DatabaseRerankConfig> resolve(String purpose) {
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotSupplier.get();
        DatabaseRerankConfig databaseConfig = snapshot.rerankConfigs().get(purpose);
        if (databaseConfig != null) {
            return Optional.of(databaseConfig);
        }
        if (!canUseLegacyFallback(snapshot, purpose)) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyResolutionSupplier.get().config());
    }

    public Optional<String> invalidReason(String purpose) {
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotSupplier.get();
        String invalidReason = snapshot.invalidRerankPurposes().get(purpose);
        if (invalidReason != null) {
            return Optional.of(invalidReason);
        }
        if (!canUseLegacyFallback(snapshot, purpose)) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyResolutionSupplier.get().invalidReason());
    }

    private boolean canUseLegacyFallback(
            DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot,
            String purpose
    ) {
        return PURPOSE_KB_QUERY_RERANK.equals(purpose)
                && !snapshot.invalidRerankPurposes().containsKey(purpose)
                && !snapshot.rerankConfigs().containsKey(purpose);
    }

    private static LegacyRerankResolution resolveLegacyRerank(RerankProperties rerankProperties) {
        if (rerankProperties == null || !rerankProperties.isEnabled()) {
            return LegacyRerankResolution.empty();
        }
        if (isBlank(rerankProperties.getApiUrl())) {
            return invalidLegacyResolution("apiUrl is invalid");
        }
        if (isBlank(rerankProperties.getApiKey())) {
            return invalidLegacyResolution("apiKey is invalid");
        }
        if (isBlank(rerankProperties.getModel())) {
            return invalidLegacyResolution("model is invalid");
        }
        if (rerankProperties.getTimeoutMs() <= 0) {
            return invalidLegacyResolution("timeoutMs is invalid");
        }
        int preRerankCandidateCap = rerankProperties.getPreRerankCandidateCap();
        if (preRerankCandidateCap <= 0 || preRerankCandidateCap > MAX_PRE_RERANK_CANDIDATE_CAP) {
            return invalidLegacyResolution(
                    "preRerankCandidateCap must be between 1 and " + MAX_PRE_RERANK_CANDIDATE_CAP
            );
        }
        int stage1TopN = rerankProperties.getStage1TopN();
        if (stage1TopN <= 0 || stage1TopN > preRerankCandidateCap) {
            return invalidLegacyResolution("stage1TopN must be <= preRerankCandidateCap");
        }
        return LegacyRerankResolution.valid(buildLegacyConfig(rerankProperties, preRerankCandidateCap, stage1TopN));
    }

    private static DatabaseRerankConfig buildLegacyConfig(
            RerankProperties rerankProperties,
            int preRerankCandidateCap,
            int stage1TopN
    ) {
        return new DatabaseRerankConfig(
                PURPOSE_KB_QUERY_RERANK,
                LEGACY_FILE_CONFIG_PROVIDER_KEY,
                rerankProperties.getApiUrl(),
                rerankProperties.getApiKey(),
                rerankProperties.getModel(),
                rerankProperties.getTimeoutMs(),
                preRerankCandidateCap,
                stage1TopN,
                rerankProperties.getStage1RelevanceThreshold(),
                rerankProperties.isDualQueryEnabled()
        );
    }

    private static LegacyRerankResolution invalidLegacyResolution(String detail) {
        return LegacyRerankResolution.invalid("Legacy rerank " + detail + " for purpose: " + PURPOSE_KB_QUERY_RERANK);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LegacyRerankResolution(DatabaseRerankConfig config, String invalidReason) {

        private static LegacyRerankResolution empty() {
            return new LegacyRerankResolution(null, null);
        }

        private static LegacyRerankResolution valid(DatabaseRerankConfig config) {
            return new LegacyRerankResolution(config, null);
        }

        private static LegacyRerankResolution invalid(String invalidReason) {
            return new LegacyRerankResolution(null, invalidReason);
        }
    }
}
