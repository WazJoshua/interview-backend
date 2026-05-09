package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.ContextSelectionProperties;
import com.josh.interviewj.ragqa.model.ContextAssemblyResult;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageAwareContextSelectorTest {

    private ContextSelectionProperties properties;
    private CoverageAwareContextSelector selector;

    @BeforeEach
    void setUp() {
        properties = new ContextSelectionProperties();
        properties.setTokenBudget(120);
        properties.setMaxBlocks(8);
        properties.setDocumentMonopolyCap(3);
        properties.setSectionMonopolyCap(2);
        properties.setSparseRescueBonus(0.1D);
        properties.setConsensusBonus(0.05D);
        selector = new CoverageAwareContextSelector(properties);
    }

    @Test
    void select_HighScoreBlocks_SelectedWithinTokenBudget() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "A", List.of("SecA"), 40, 0.90D, Set.of(dense())),
                block(2L, "B", List.of("SecB"), 35, 0.85D, Set.of(dense())),
                block(3L, "C", List.of("SecC"), 60, 0.40D, Set.of(dense()))
        ), 90);

        assertThat(result.selectedBlocks()).hasSize(2);
        assertThat(result.selectedBlocks())
                .extracting(ContextBlock::documentId)
                .containsExactly(1L, 2L);
        assertThat(result.totalEstimatedTokens()).isEqualTo(75);
    }

    @Test
    void select_DocumentMonopoly_CappedAt3BlocksPerDocument() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "A1", List.of("S1"), 20, 0.95D, Set.of(dense())),
                block(1L, "A2", List.of("S2"), 20, 0.94D, Set.of(dense())),
                block(1L, "A3", List.of("S3"), 20, 0.93D, Set.of(dense())),
                block(1L, "A4", List.of("S4"), 20, 0.92D, Set.of(dense())),
                block(2L, "B1", List.of("S1"), 20, 0.60D, Set.of(dense()))
        ), 200);

        assertThat(result.selectedBlocks()).hasSize(4);
        assertThat(result.selectedBlocks().stream().filter(block -> block.documentId().equals(1L)).count()).isEqualTo(3);
        assertThat(result.selectedBlocks()).extracting(ContextBlock::documentId).contains(2L);
    }

    @Test
    void select_SectionMonopoly_CappedAt2BlocksPerSection() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "A1", List.of("Shared"), 20, 0.95D, Set.of(dense())),
                block(2L, "A2", List.of("Shared"), 20, 0.94D, Set.of(dense())),
                block(3L, "A3", List.of("Shared"), 20, 0.93D, Set.of(dense())),
                block(4L, "B1", List.of("Other"), 20, 0.70D, Set.of(dense()))
        ), 200);

        assertThat(result.selectedBlocks()).hasSize(3);
        assertThat(result.selectedBlocks().stream().filter(block -> block.sectionPath().equals(List.of("Shared"))).count()).isEqualTo(2);
    }

    @Test
    void select_LargeBlockVsMultipleSmallBlocks_PrefersHigherCoverage() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "Large", List.of("Large"), 100, 0.95D, Set.of(dense())),
                block(2L, "SmallA", List.of("SmallA"), 45, 0.82D, Set.of(dense())),
                block(3L, "SmallB", List.of("SmallB"), 45, 0.81D, Set.of(dense()))
        ), 100);

        assertThat(result.selectedBlocks()).hasSize(2);
        assertThat(result.selectedBlocks())
                .extracting(ContextBlock::documentId)
                .containsExactly(2L, 3L);
    }

    @Test
    void select_SparseOnlyRescue_GetsBonusScore() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "dense", List.of("A"), 40, 0.70D, Set.of(dense())),
                block(2L, "sparse", List.of("B"), 40, 0.65D, Set.of(sparse()))
        ), 40);

        assertThat(result.selectedBlocks()).singleElement().satisfies(block ->
                assertThat(block.documentId()).isEqualTo(2L)
        );
    }

    @Test
    void select_DenseSparseConsensus_GetsBonusScore() {
        ContextAssemblyResult result = selector.select(List.of(
                block(1L, "dense", List.of("A"), 40, 0.80D, Set.of(dense())),
                block(2L, "consensus", List.of("B"), 40, 0.77D, Set.of(dense(), sparse()))
        ), 40);

        assertThat(result.selectedBlocks()).singleElement().satisfies(block ->
                assertThat(block.documentId()).isEqualTo(2L)
        );
    }

    @Test
    void select_EmptyBlocks_ReturnsDegradedResult() {
        ContextAssemblyResult result = selector.select(List.of(), 100);

        assertThat(result.selectedBlocks()).isEmpty();
        assertThat(result.degradedReason()).isEqualTo("no_blocks_available");
    }

    private ContextBlock block(
            Long documentId,
            String label,
            List<String> sectionPath,
            int estimatedTokens,
            double stage1BestScore,
            Set<RetrievalProvenance> provenances
    ) {
        int syntheticChunkIndex = Math.abs((documentId + ":" + label).hashCode());
        return new ContextBlock(
                documentId,
                UUID.nameUUIDFromBytes(("doc-" + documentId).getBytes()),
                "Doc-" + documentId,
                sectionPath,
                List.of(syntheticChunkIndex),
                List.of(syntheticChunkIndex),
                label,
                estimatedTokens,
                stage1BestScore,
                Map.of(),
                provenances,
                ContextBlock.AssemblyStrategy.SECTION_PRIORITY
        );
    }

    private RetrievalProvenance dense() {
        return new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE);
    }

    private RetrievalProvenance sparse() {
        return new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.SPARSE);
    }
}
