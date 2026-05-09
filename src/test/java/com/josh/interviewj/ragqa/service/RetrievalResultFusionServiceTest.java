package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.FusedRetrievalResult;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalResultFusionServiceTest {

    private final RetrievalResultFusionService fusionService = new RetrievalResultFusionService();

    @Test
    void fuse_UsesRrfAndStableTieBreak() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, docA, 1L, 0, 0.95, 1),
                        chunk(QueryVariant.REWRITE, docA, 1L, 0, 0.90, 2),
                        chunk(QueryVariant.ORIGINAL, docB, 2L, 0, 0.95, 1)
                ),
                2
        );

        assertThat(result.selectedChunks()).hasSize(2);
        assertThat(result.selectedChunks().getFirst().documentId()).isEqualTo(1L);
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.deduplicatedCount()).isEqualTo(2);
        assertThat(result.overlapHitCount()).isEqualTo(1);
    }

    @Test
    void fuse_FirstPassLimitsTwoChunksPerDocumentThenBackfills() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, docA, 1L, 0, 0.95, 1),
                        chunk(QueryVariant.REWRITE, docA, 1L, 1, 0.94, 2),
                        chunk(QueryVariant.ORIGINAL, docA, 1L, 2, 0.93, 3),
                        chunk(QueryVariant.ORIGINAL, docB, 2L, 0, 0.92, 4)
                ),
                3
        );

        assertThat(result.selectedChunks()).hasSize(3);
        assertThat(result.selectedChunks().stream().filter(chunk -> chunk.documentId().equals(1L)).count()).isEqualTo(2);
        assertThat(result.selectedChunks().stream().anyMatch(chunk -> chunk.documentId().equals(2L))).isTrue();
    }

    @Test
    void fuse_DoesNotDoubleCountFirstBranchHitDuringAggregation() {
        UUID faq = UUID.randomUUID();
        UUID auth = UUID.randomUUID();

        FusedRetrievalResult result = fusionService.fuse(
                List.of(
                        chunk(QueryVariant.ORIGINAL, faq, 10L, 0, 0.78, 1),
                        chunk(QueryVariant.ORIGINAL, auth, 11L, 0, 0.72, 3),
                        chunk(QueryVariant.REWRITE, auth, 11L, 0, 0.95, 1),
                        chunk(QueryVariant.REWRITE, faq, 10L, 0, 0.51, 4)
                ),
                1
        );

        assertThat(result.selectedChunks()).singleElement().satisfies(chunk ->
                assertThat(chunk.documentId()).isEqualTo(11L)
        );
    }

    private RetrievedChunk chunk(
            QueryVariant queryVariant,
            UUID documentExternalId,
            Long documentId,
            int chunkIndex,
            double similarity,
            int rank
    ) {
        return new RetrievedChunk(
                queryVariant,
                RetrievalMode.DENSE,
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
