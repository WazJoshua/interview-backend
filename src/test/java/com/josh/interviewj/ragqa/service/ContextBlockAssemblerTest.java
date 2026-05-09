package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.ContextAssemblyProperties;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.ContextBlockAssemblyResult;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.repository.ChunkNeighborRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextBlockAssemblerTest {

    private ChunkNeighborRepository chunkNeighborRepository;
    private ContextAssemblyProperties properties;
    private ObjectMapper objectMapper;
    private ContextBlockAssembler assembler;

    @BeforeEach
    void setUp() {
        chunkNeighborRepository = mock(ChunkNeighborRepository.class);
        properties = new ContextAssemblyProperties();
        properties.setEnabled(true);
        properties.setAdjacencyWindowSize(1);
        properties.setMaxBlockTokens(200);
        properties.setOverlapMergeThreshold(0.6D);
        objectMapper = JsonMapper.builder().build();
        assembler = new ContextBlockAssembler(chunkNeighborRepository, properties, objectMapper);
    }

    @Test
    void assemble_SectionPathAvailable_GroupsBySameSection() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findDocumentChunks(10L)).thenReturn(List.of(
                documentChunk(0, "intro", metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Intro"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(1, "body-a", metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Section A"), List.of("PARAGRAPH"), 2), 20),
                documentChunk(2, "body-b", metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Section A"), List.of("LIST_ITEM"), 2), 20),
                documentChunk(3, "appendix", metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Appendix"), List.of("PARAGRAPH"), 3), 20)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(10L, documentExternalId, 1, 0.91D, metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Section A"), List.of("PARAGRAPH"), 2)),
                seed(10L, documentExternalId, 2, 0.88D, metadata("MARKDOWN", 1.0D, false, List.of("Guide", "Section A"), List.of("LIST_ITEM"), 2))
        ));

        assertThat(result.degraded()).isFalse();
        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.SECTION_PRIORITY);
            assertThat(block.sectionPath()).containsExactly("Guide", "Section A");
            assertThat(block.seedChunkIndexes()).containsExactly(1, 2);
            assertThat(block.includedChunkIndexes()).containsExactly(1, 2);
            assertThat(block.mergedText()).contains("body-a").contains("body-b");
        });
        verify(chunkNeighborRepository, times(1)).findDocumentChunks(10L);
    }

    @Test
    void assemble_SectionPathAvailable_LoadsOrderedDocumentChunksForSameSection() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findDocumentChunks(20L)).thenReturn(List.of(
                documentChunk(0, "a0", metadata("DOCX", 1.0D, false, List.of("Chapter 1"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(1, "a1", metadata("DOCX", 1.0D, false, List.of("Chapter 1"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(2, "a2", metadata("DOCX", 1.0D, false, List.of("Chapter 1"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(3, "b0", metadata("DOCX", 1.0D, false, List.of("Chapter 2"), List.of("PARAGRAPH"), 2), 20)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(20L, documentExternalId, 1, 0.93D, metadata("DOCX", 1.0D, false, List.of("Chapter 1"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block ->
                assertThat(block.includedChunkIndexes()).containsExactly(0, 1, 2)
        );
    }

    @Test
    void assemble_SectionPathLowConfidence_FallsBackToAdjacency() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findNeighborChunks(eq(30L), eq(3), eq(5))).thenReturn(List.of(
                neighborChunk(3, "left", metadata("PDF", 0.4D, true, List.of(), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(4, "seed", metadata("PDF", 0.4D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(5, "right", metadata("PDF", 0.4D, true, List.of(), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(30L, documentExternalId, 4, 0.72D, metadata("PDF", 0.4D, true, List.of("Guess"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK);
            assertThat(block.includedChunkIndexes()).containsExactly(3, 4, 5);
        });
        verify(chunkNeighborRepository, times(1)).findNeighborChunks(30L, 3, 5);
    }

    @Test
    void assemble_OverlappingBlocks_MergedWhenOverlapExceedsThreshold() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findNeighborChunks(eq(40L), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int fromIndex = invocation.getArgument(1);
                    int toIndex = invocation.getArgument(2);
                    return java.util.stream.IntStream.rangeClosed(fromIndex, toIndex)
                            .mapToObj(index -> neighborChunk(
                                    index,
                                    "chunk-" + index,
                                    metadata("PDF", 0.4D, true, List.of("Guessed"), List.of("PARAGRAPH"), 1),
                                    10
                            ))
                            .toList();
                });

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(40L, documentExternalId, 4, 0.84D, metadata("PDF", 0.4D, true, List.of(), List.of("PARAGRAPH"), 1)),
                seed(40L, documentExternalId, 5, 0.80D, metadata("PDF", 0.4D, true, List.of(), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block ->
                assertThat(block.includedChunkIndexes()).containsExactly(3, 4, 5, 6)
        );
        assertThat(result.mergedBlockCount()).isEqualTo(1);
        assertThat(result.overlapFilteredCount()).isEqualTo(1);
    }

    @Test
    void assemble_OverlappingBlocks_MergeRemovesDuplicateTextAndRecomputesTokens() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findNeighborChunks(eq(41L), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int fromIndex = invocation.getArgument(1);
                    int toIndex = invocation.getArgument(2);
                    return java.util.stream.IntStream.rangeClosed(fromIndex, toIndex)
                            .mapToObj(index -> neighborChunk(
                                    index,
                                    "chunk-" + index,
                                    null,
                                    10
                            ))
                            .toList();
                });

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(41L, documentExternalId, 4, 0.84D, "{malformed-json"),
                seed(41L, documentExternalId, 5, 0.80D, "{malformed-json")
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.mergedText()).isEqualTo("chunk-3\n\nchunk-4\n\nchunk-5\n\nchunk-6");
            assertThat(block.estimatedTokens()).isEqualTo(52);
        });
    }

    @Test
    void assemble_OverlappingBlocks_MergedBlockCannotExceedMaxTokens() {
        UUID documentExternalId = UUID.randomUUID();
        properties.setMaxBlockTokens(40);
        when(chunkNeighborRepository.findNeighborChunks(eq(42L), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int fromIndex = invocation.getArgument(1);
                    int toIndex = invocation.getArgument(2);
                    return java.util.stream.IntStream.rangeClosed(fromIndex, toIndex)
                            .mapToObj(index -> neighborChunk(
                                    index,
                                    "chunk-" + index,
                                    null,
                                    8
                            ))
                            .toList();
                });

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(42L, documentExternalId, 4, 0.84D, "{malformed-json"),
                seed(42L, documentExternalId, 5, 0.80D, "{malformed-json")
        ));

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.mergedBlockCount()).isZero();
        assertThat(result.blocks()).allSatisfy(block ->
                assertThat(block.estimatedTokens()).isLessThanOrEqualTo(40)
        );
    }

    @Test
    void assemble_LargeSection_TruncatedToMaxBlockTokens() {
        UUID documentExternalId = UUID.randomUUID();
        properties.setMaxBlockTokens(30);
        when(chunkNeighborRepository.findDocumentChunks(50L)).thenReturn(List.of(
                documentChunk(0, "alpha-alpha", metadata("MARKDOWN", 1.0D, false, List.of("Section"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(1, "beta-beta", metadata("MARKDOWN", 1.0D, false, List.of("Section"), List.of("PARAGRAPH"), 1), 20),
                documentChunk(2, "gamma-gamma", metadata("MARKDOWN", 1.0D, false, List.of("Section"), List.of("PARAGRAPH"), 1), 20)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(50L, documentExternalId, 1, 0.90D, metadata("MARKDOWN", 1.0D, false, List.of("Section"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.includedChunkIndexes()).containsExactly(0);
            assertThat(block.estimatedTokens()).isLessThanOrEqualTo(30);
        });
    }

    @Test
    void assemble_NoMetadata_FallsBackToSingleChunk() {
        UUID documentExternalId = UUID.randomUUID();

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(60L, documentExternalId, 8, 0.55D, null)
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.SINGLE_CHUNK);
            assertThat(block.includedChunkIndexes()).containsExactly(8);
            assertThat(block.mergedText()).isEqualTo("doc-60-8");
        });
    }

    @Test
    void assemble_MalformedMetadata_FallsBackToAdjacency() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findNeighborChunks(eq(61L), eq(7), eq(9))).thenReturn(List.of(
                neighborChunk(7, "left", null, 10),
                neighborChunk(8, "seed", null, 10),
                neighborChunk(9, "right", null, 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(61L, documentExternalId, 8, 0.55D, "{malformed-json")
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK);
            assertThat(block.includedChunkIndexes()).containsExactly(7, 8, 9);
        });
    }

    @Test
    void assemble_SameSectionSeparatedSeeds_RetainsAllSeedSegments() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findDocumentChunks(62L)).thenReturn(List.of(
                documentChunk(1, "seg-a1", metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(2, "seg-a2", metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(4, "seg-b1", metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(5, "seg-b2", metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(62L, documentExternalId, 2, 0.91D, metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1)),
                seed(62L, documentExternalId, 5, 0.89D, metadata("DOCX", 1.0D, false, List.of("Guide", "Section"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.seedChunkIndexes()).containsExactly(2, 5);
            assertThat(block.includedChunkIndexes()).containsExactly(1, 2, 4, 5);
        });
    }

    @Test
    void assemble_OverlappingBlocksAcrossDifferentSections_DoNotMerge() {
        UUID documentExternalId = UUID.randomUUID();
        properties.setAdjacencyWindowSize(2);
        when(chunkNeighborRepository.findDocumentChunks(63L)).thenReturn(List.of(
                documentChunk(2, "sec-2", metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(3, "sec-3", metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(4, "sec-4", metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(5, "sec-5", metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1), 10),
                documentChunk(6, "sec-6", metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1), 10)
        ));
        when(chunkNeighborRepository.findNeighborChunks(eq(63L), eq(4), eq(8))).thenReturn(List.of(
                neighborChunk(4, "adj-4", metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(5, "adj-5", metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(6, "adj-6", metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(7, "adj-7", metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(8, "adj-8", metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(63L, documentExternalId, 4, 0.91D, metadata("DOCX", 1.0D, false, List.of("Guide", "A"), List.of("PARAGRAPH"), 1)),
                seed(63L, documentExternalId, 6, 0.70D, metadata("PDF", 0.2D, true, List.of("Guess"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.mergedBlockCount()).isZero();
    }

    @Test
    void assemble_Disabled_ReturnsDegradedResult() {
        properties.setEnabled(false);

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(70L, UUID.randomUUID(), 0, 0.80D, null)
        ));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo("context_assembly_disabled");
    }

    @Test
    void assemble_AdjacencyExpansion_IncludesNeighborChunks() {
        UUID documentExternalId = UUID.randomUUID();
        properties.setAdjacencyWindowSize(2);
        when(chunkNeighborRepository.findNeighborChunks(eq(80L), eq(8), eq(12))).thenReturn(List.of(
                neighborChunk(8, "left-2", metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(9, "left-1", metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(10, "seed", metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(11, "right-1", metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(12, "right-2", metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(80L, documentExternalId, 10, 0.75D, metadata("PDF", 0.4D, true, List.of("Noisy"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK);
            assertThat(block.includedChunkIndexes()).containsExactly(8, 9, 10, 11, 12);
        });
    }

    @Test
    void assemble_PdfLowConfidence_UsesAdjacencyOnly() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findNeighborChunks(eq(81L), eq(5), eq(7))).thenReturn(List.of(
                neighborChunk(5, "left", metadata("PDF", 0.4D, true, List.of("Guessed"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(6, "seed", metadata("PDF", 0.4D, true, List.of("Guessed"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(7, "right", metadata("PDF", 0.4D, true, List.of("Guessed"), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(81L, documentExternalId, 6, 0.70D, metadata("PDF", 0.4D, true, List.of("Guessed"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.blocks()).singleElement().satisfies(block ->
                assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK)
        );
    }

    @Test
    void assemble_SparseOnlySeedWithMetadata_CanStillUseSectionPriority() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findDocumentChunks(90L)).thenReturn(List.of(
                documentChunk(1, "section-data", metadata("DOCX", 0.9D, false, List.of("Guide", "S1"), List.of("PARAGRAPH"), 1), 20)
        ));

        RankedChunkCandidate sparseSeed = new RankedChunkCandidate(
                90L,
                documentExternalId,
                "Doc-90",
                1,
                "section-data",
                metadata("DOCX", 0.9D, false, List.of("Guide", "S1"), List.of("PARAGRAPH"), 1),
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.SPARSE)),
                0.69D,
                0D,
                0.69D
        );

        ContextBlockAssemblyResult result = assembler.assemble(List.of(sparseSeed));

        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.SECTION_PRIORITY);
            assertThat(block.provenances())
                    .extracting(provenance -> provenance.retrievalMode().name())
                    .containsExactly("SPARSE");
        });
        verify(chunkNeighborRepository, never()).findNeighborChunks(eq(90L), anyInt(), anyInt());
    }

    @Test
    void assemble_SectionExtractionFails_ReturnsDegradedReasonAndFallbackCount() {
        UUID documentExternalId = UUID.randomUUID();
        when(chunkNeighborRepository.findDocumentChunks(100L)).thenReturn(List.of(
                documentChunk(0, "other", metadata("MARKDOWN", 1.0D, false, List.of("Other"), List.of("PARAGRAPH"), 1), 20)
        ));
        when(chunkNeighborRepository.findNeighborChunks(eq(100L), eq(3), eq(5))).thenReturn(List.of(
                neighborChunk(3, "left", metadata("MARKDOWN", 1.0D, false, List.of("Target"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(4, "seed", metadata("MARKDOWN", 1.0D, false, List.of("Target"), List.of("PARAGRAPH"), 1), 10),
                neighborChunk(5, "right", metadata("MARKDOWN", 1.0D, false, List.of("Target"), List.of("PARAGRAPH"), 1), 10)
        ));

        ContextBlockAssemblyResult result = assembler.assemble(List.of(
                seed(100L, documentExternalId, 4, 0.82D, metadata("MARKDOWN", 1.0D, false, List.of("Target"), List.of("PARAGRAPH"), 1))
        ));

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo("section_extraction_failed");
        assertThat(result.sectionFallbackCount()).isEqualTo(1);
        assertThat(result.blocks()).singleElement().satisfies(block ->
                assertThat(block.assemblyStrategy()).isEqualTo(ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK)
        );
    }

    private RankedChunkCandidate seed(
            Long documentId,
            UUID documentExternalId,
            int chunkIndex,
            double stage1Score,
            String metadata
    ) {
        return new RankedChunkCandidate(
                documentId,
                documentExternalId,
                "Doc-" + documentId,
                chunkIndex,
                "doc-" + documentId + "-" + chunkIndex,
                metadata,
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                stage1Score,
                stage1Score,
                stage1Score
        );
    }

    private ChunkNeighborRepository.DocumentChunkSliceProjection documentChunk(
            Integer chunkIndex,
            String content,
            String metadata,
            Integer tokenCount
    ) {
        return new ChunkNeighborRepository.DocumentChunkSliceProjection() {
            @Override
            public Integer getChunkIndex() {
                return chunkIndex;
            }

            @Override
            public String getContent() {
                return content;
            }

            @Override
            public String getMetadata() {
                return metadata;
            }

            @Override
            public Integer getTokenCount() {
                return tokenCount;
            }
        };
    }

    private ChunkNeighborRepository.NeighborChunkProjection neighborChunk(
            Integer chunkIndex,
            String content,
            String metadata,
            Integer tokenCount
    ) {
        return new ChunkNeighborRepository.NeighborChunkProjection() {
            @Override
            public Integer getChunkIndex() {
                return chunkIndex;
            }

            @Override
            public String getContent() {
                return content;
            }

            @Override
            public String getMetadata() {
                return metadata;
            }

            @Override
            public Integer getTokenCount() {
                return tokenCount;
            }
        };
    }

    private String metadata(
            String sourceType,
            double sectionPathConfidence,
            boolean sectionPathNormalized,
            List<String> sectionPath,
            List<String> blockTypes,
            Integer pageNumber
    ) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "sourceType", sourceType,
                    "sectionPathConfidence", sectionPathConfidence,
                    "sectionPathNormalized", sectionPathNormalized,
                    "sectionPath", sectionPath,
                    "blockTypes", blockTypes,
                    "pageNumberRange", pageNumber == null ? java.util.Map.of() : java.util.Map.of("start", pageNumber, "end", pageNumber)
            ));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
