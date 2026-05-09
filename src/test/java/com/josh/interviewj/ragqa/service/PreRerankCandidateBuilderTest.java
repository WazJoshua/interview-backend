package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreRerankCandidateBuilderTest {

    private final PreRerankCandidateBuilder builder = new PreRerankCandidateBuilder();

    @Test
    void build_AggregatesByDocumentIdAndChunkIndex_ProducesUniqueRrf() {
        UUID documentExternalId = UUID.randomUUID();

        List<PreRerankCandidate> result = builder.build(List.of(
                chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, 10L, 2, 0.91D, 1, "{\"sectionPath\":[\"A\"]}"),
                chunk(QueryVariant.REWRITE, RetrievalMode.DENSE, documentExternalId, 10L, 2, 0.83D, 4, "{\"sectionPath\":[\"A\"]}")
        ), 24);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.documentId()).isEqualTo(10L);
            assertThat(candidate.chunkIndex()).isEqualTo(2);
            assertThat(candidate.bestDenseSimilarity()).isEqualTo(0.91D);
            assertThat(candidate.bestDenseRank()).isEqualTo(1);
            assertThat(candidate.rrfScore()).isCloseTo((1D / 61D) + (1D / 64D), within(1.0E-9));
            assertThat(candidate.metadata()).isEqualTo("{\"sectionPath\":[\"A\"]}");
        });
    }

    @Test
    void build_MergesProvenancesFromMultipleBranches() {
        UUID documentExternalId = UUID.randomUUID();

        List<PreRerankCandidate> result = builder.build(List.of(
                chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, 20L, 0, 0.85D, 1, null),
                chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, documentExternalId, 20L, 0, 2.0D, 2, null),
                chunk(QueryVariant.REWRITE, RetrievalMode.DENSE, documentExternalId, 20L, 0, 0.82D, 3, null)
        ), 24);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.hasDenseProvenance()).isTrue();
            assertThat(candidate.hasSparseProvenance()).isTrue();
            assertThat(candidate.sparseOnlyRescue()).isFalse();
            assertThat(candidate.provenances())
                    .extracting(provenance -> provenance.queryVariant().name() + "_" + provenance.retrievalMode().name())
                    .containsExactlyInAnyOrder("ORIGINAL_DENSE", "ORIGINAL_SPARSE", "REWRITE_DENSE");
        });
    }

    @Test
    void build_CapsAt24_SortedByRrfDescWithConsensusFirst() {
        UUID documentExternalId = UUID.randomUUID();

        List<RetrievedChunk> rawCandidates = new java.util.ArrayList<>();
        rawCandidates.add(chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, 1L, 0, 0.91D, 1, null));
        rawCandidates.add(chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, documentExternalId, 2L, 0, 3.0D, 1, null));
        rawCandidates.add(chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, 3L, 0, 0.86D, 1, null));
        rawCandidates.add(chunk(QueryVariant.REWRITE, RetrievalMode.SPARSE, documentExternalId, 3L, 0, 2.2D, 1, null));
        for (long documentId = 4L; documentId <= 26L; documentId++) {
            rawCandidates.add(chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, documentId, 0, 0.50D, (int) documentId, null));
        }

        List<PreRerankCandidate> result = builder.build(rawCandidates, 24);

        assertThat(result).hasSize(24);
        assertThat(result.getFirst().documentId()).isEqualTo(3L);
        assertThat(result.get(1).documentId()).isEqualTo(2L);
        assertThat(result.get(2).documentId()).isEqualTo(1L);
        assertThat(result).noneMatch(candidate -> candidate.documentId().equals(26L));
    }

    @Test
    void build_SparseOnlyRescue_PreservedWhenUnderCap() {
        UUID documentExternalId = UUID.randomUUID();

        List<PreRerankCandidate> result = builder.build(List.of(
                chunk(QueryVariant.ORIGINAL, RetrievalMode.SPARSE, documentExternalId, 30L, 0, 3.0D, 1, null),
                chunk(QueryVariant.ORIGINAL, RetrievalMode.DENSE, documentExternalId, 31L, 0, 0.60D, 2, null)
        ), 24);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().documentId()).isEqualTo(30L);
        assertThat(result.getFirst().sparseOnlyRescue()).isTrue();
        assertThat(result.getFirst().hasDenseProvenance()).isFalse();
        assertThat(result.getFirst().hasSparseProvenance()).isTrue();
    }

    @Test
    void build_MoreThan24Unique_TruncatesToExactly24() {
        UUID documentExternalId = UUID.randomUUID();
        List<RetrievedChunk> rawCandidates = java.util.stream.LongStream.rangeClosed(1, 30)
                .mapToObj(documentId -> chunk(
                        QueryVariant.ORIGINAL,
                        RetrievalMode.DENSE,
                        documentExternalId,
                        documentId,
                        0,
                        0.40D,
                        (int) documentId,
                        null
                ))
                .toList();

        List<PreRerankCandidate> result = builder.build(rawCandidates, 24);

        assertThat(result).hasSize(24);
        assertThat(result)
                .extracting(PreRerankCandidate::documentId)
                .contains(1L, 24L)
                .doesNotContain(25L, 30L);
    }

    private RetrievedChunk chunk(
            QueryVariant queryVariant,
            RetrievalMode retrievalMode,
            UUID documentExternalId,
            Long documentId,
            int chunkIndex,
            double similarity,
            int branchRank,
            String metadata
    ) {
        return new RetrievedChunk(
                queryVariant,
                retrievalMode,
                documentExternalId,
                documentId,
                "Doc-" + documentId,
                chunkIndex,
                "Chunk-" + documentId + "-" + chunkIndex,
                similarity,
                branchRank,
                metadata
        );
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
