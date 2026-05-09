package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.QueryProfile;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkRerankServiceTest {

    private AiOperationGateway aiOperationGateway;
    private AtomicReference<DatabaseRerankConfig> configRef;
    private ChunkRerankService service;

    @BeforeEach
    void setUp() {
        aiOperationGateway = mock(AiOperationGateway.class);
        configRef = new AtomicReference<>(config(24, 2, 0.1D, true));
        DatabaseRerankConfigResolver resolver = new DatabaseRerankConfigResolver(() -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("kb_query_rerank", configRef.get()),
                Map.of()
        ));
        service = new ChunkRerankService(aiOperationGateway, resolver);
        lenient().when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                0L,
                "KNOWLEDGE_BASE_QUERY",
                "biz-1",
                "kb_query_rerank",
                List.of("KB_QUERY_CREDITS"),
                Map.of()
        ));
    }

    @Test
    void rerank_Success_ReturnsSortedByRelevanceScore() {
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.75D, 0.70D, false, true),
                candidate(2L, 0, 0.70D, 0.65D, false, true),
                candidate(3L, 0, 0.65D, 0.60D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromRerank("default", response(
                        scored(1, 0.91D),
                        scored(0, 0.55D),
                        scored(2, 0.22D)
                )));

        ChunkRerankService.ChunkRerankResult result = service.rerank(
                candidates,
                normalizedQuery("normalized", QueryProfile.none()),
                RewriteResult.notAttempted("normalized")
        );

        assertThat(result.degraded()).isFalse();
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::documentId)
                .containsExactly(2L, 1L);
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::stage1RelevanceScore)
                .containsExactly(0.91D, 0.55D);
    }

    @Test
    void rerank_DualQuery_UsesMaxScore() {
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.80D, 0.75D, false, true),
                candidate(2L, 0, 0.78D, 0.72D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(
                        AiInvocationResult.fromRerank("default", response(
                        scored(0, 0.41D),
                        scored(1, 0.62D)
                        )),
                        AiInvocationResult.fromRerank("default", response(
                        scored(0, 0.95D),
                        scored(1, 0.30D)
                        ))
                );

        ChunkRerankService.ChunkRerankResult result = service.rerank(
                candidates,
                normalizedQuery("normalized", new QueryProfile(false, true, false, true, false)),
                RewriteResult.succeeded("rewritten")
        );

        assertThat(result.degraded()).isFalse();
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::documentId)
                .containsExactly(1L, 2L);
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::stage1RelevanceScore)
                .containsExactly(0.95D, 0.62D);
    }

    @Test
    void rerank_DualQuery_SkippedWhenRewriteNotSucceeded() {
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.80D, 0.75D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromRerank("default", response(scored(0, 0.88D))));

        service.rerank(
                candidates,
                normalizedQuery("normalized", new QueryProfile(false, false, false, true, false)),
                RewriteResult.failed("normalized", null)
        );

        verify(aiOperationGateway, times(1)).executeInvocation(any(), any(), any());
    }

    @Test
    void rerank_DualQuery_SkippedWhenProfileNoSemanticDrift() {
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.80D, 0.75D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromRerank("default", response(scored(0, 0.88D))));

        service.rerank(
                candidates,
                normalizedQuery("normalized", QueryProfile.none()),
                RewriteResult.succeeded("rewritten")
        );

        verify(aiOperationGateway, times(1)).executeInvocation(any(), any(), any());
    }

    @Test
    void rerank_Timeout_DegradesToPreRerankOrder() {
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.91D, 0.75D, false, true),
                candidate(2L, 0, 0.80D, 0.72D, true, false),
                candidate(3L, 0, 0.70D, 0.71D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new RerankException("timed out"));

        ChunkRerankService.ChunkRerankResult result = service.rerank(
                candidates,
                normalizedQuery("normalized", QueryProfile.none()),
                RewriteResult.notAttempted("normalized")
        );

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo("timed out");
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::documentId)
                .containsExactly(1L, 2L);
        assertThat(result.rankedCandidates())
                .extracting(RankedChunkCandidate::stage1RelevanceScore)
                .containsExactly(0.91D, 0.80D);
    }

    @Test
    void rerank_TopNRetention_ReturnsConfiguredCount() {
        configRef.set(config(24, 1, 0.1D, true));
        List<PreRerankCandidate> candidates = List.of(
                candidate(1L, 0, 0.91D, 0.75D, false, true),
                candidate(2L, 0, 0.80D, 0.72D, false, true)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromRerank("default", response(
                        scored(0, 0.80D),
                        scored(1, 0.79D)
                )));

        ChunkRerankService.ChunkRerankResult result = service.rerank(
                candidates,
                normalizedQuery("normalized", QueryProfile.none()),
                RewriteResult.notAttempted("normalized")
        );

        assertThat(result.rankedCandidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.documentId()).isEqualTo(1L)
        );
    }

    @Test
    void rerank_EmptyCandidates_ReturnsEmpty() {
        ChunkRerankService.ChunkRerankResult result = service.rerank(
                List.of(),
                normalizedQuery("normalized", QueryProfile.none()),
                RewriteResult.notAttempted("normalized")
        );

        assertThat(result.rankedCandidates()).isEmpty();
        assertThat(result.degraded()).isFalse();
        verify(aiOperationGateway, times(0)).executeInvocation(any(), any(), any());
    }

    private NormalizedQuery normalizedQuery(String normalizedText, QueryProfile profile) {
        return new NormalizedQuery(
                normalizedText,
                normalizedText,
                List.of(),
                List.of(),
                List.of(),
                profile
        );
    }

    private RerankResponse response(RerankResponse.ScoredDocument... scoredDocuments) {
        return new RerankResponse(
                "Qwen3-Reranker-8B",
                12,
                List.of(scoredDocuments),
                24,
                new ProviderUsage(UsageFamily.RERANK, 1L, 12L, null, 24L, null)
        );
    }

    private RerankResponse.ScoredDocument scored(int index, double score) {
        return new RerankResponse.ScoredDocument(index, score);
    }

    private PreRerankCandidate candidate(
            Long documentId,
            int chunkIndex,
            double rrfScore,
            double denseSimilarity,
            boolean sparseOnlyRescue,
            boolean hasDense
    ) {
        return new PreRerankCandidate(
                documentId,
                UUID.nameUUIDFromBytes(("doc-" + documentId).getBytes()),
                "Doc-" + documentId,
                chunkIndex,
                "doc-" + documentId + "-" + chunkIndex,
                rrfScore,
                denseSimilarity,
                hasDense ? 1 : Integer.MAX_VALUE,
                sparseOnlyRescue,
                hasDense,
                sparseOnlyRescue,
                sparseOnlyRescue
                        ? Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.SPARSE))
                        : Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                "{\"sectionPath\":[\"Doc\"]}"
        );
    }

    private DatabaseRerankConfig config(
            int preRerankCandidateCap,
            int stage1TopN,
            double stage1RelevanceThreshold,
            boolean dualQueryEnabled
    ) {
        return new DatabaseRerankConfig(
                "kb_query_rerank",
                "db-rerank",
                "https://db-rerank.example.com/v1/rerank",
                "db-secret-key",
                "Qwen3-Reranker-8B",
                1_000,
                preRerankCandidateCap,
                stage1TopN,
                stage1RelevanceThreshold,
                dualQueryEnabled
        );
    }
}
