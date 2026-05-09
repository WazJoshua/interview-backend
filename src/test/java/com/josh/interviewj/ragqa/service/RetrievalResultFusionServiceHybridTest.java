package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.FusedRetrievalResult;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalResultFusionServiceHybridTest {

    private final RetrievalResultFusionService fusionService = new RetrievalResultFusionService();

    @Test
    void fuse_HybridHits_PreservesVariantAndModeProvenance() {
        UUID doc = UUID.randomUUID();

        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, doc, 10L, 0, 0.92D, 1),
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, doc, 10L, 0, 2.50D, 1)
                ),
                1
        );

        assertThat(result.selectedChunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.provenances())
                    .extracting(provenance -> provenance.queryVariant().name() + "_" + provenance.retrievalMode().name())
                    .containsExactlyInAnyOrder("ORIGINAL_DENSE", "ORIGINAL_SPARSE");
        });
    }

    @Test
    void fuse_SparseOnlyRescue_IncrementsRescueMetric() {
        UUID doc = UUID.randomUUID();

        FusedRetrievalResult result = fusionService.fuse(
                List.of(chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, doc, 20L, 0, 3.0D, 1)),
                1
        );

        assertThat(result.sparseOnlyRescueCount()).isEqualTo(1);
        assertThat(result.sparseSelectedCount()).isEqualTo(1);
    }

    @Test
    void fuse_SparseSelectedWithoutDenseSupport_IncrementsMismatchMetric() {
        UUID doc = UUID.randomUUID();

        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, doc, 30L, 0, 3.0D, 1),
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, doc, 30L, 0, 0.2D, 30)
                ),
                1
        );

        assertThat(result.crossBranchMismatchCount()).isEqualTo(1);
    }

    @Test
    void fuse_TieBreak_DoesNotLetSparseScoreOverrideDenseSimilarity() {
        UUID denseDoc = UUID.randomUUID();
        UUID sparseDoc = UUID.randomUUID();

        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, denseDoc, 40L, 0, 0.91D, 1),
                        chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, sparseDoc, 41L, 0, 5.00D, 1)
                ),
                1
        );

        assertThat(result.selectedChunks()).singleElement().satisfies(chunk ->
                assertThat(chunk.documentId()).isEqualTo(40L)
        );
    }

    private RetrievedChunk chunk(
            QueryVariant queryVariant,
            RetrievalMode retrievalMode,
            UUID documentExternalId,
            Long documentId,
            int chunkIndex,
            double similarity,
            int rank
    ) {
        return new RetrievedChunk(
                queryVariant,
                retrievalMode,
                documentExternalId,
                documentId,
                "Doc-" + documentId,
                chunkIndex,
                "chunk-" + chunkIndex,
                similarity,
                rank,
                "{\"sectionPath\":[\"Doc-" + documentId + "\"]}"
        );
    }
}
