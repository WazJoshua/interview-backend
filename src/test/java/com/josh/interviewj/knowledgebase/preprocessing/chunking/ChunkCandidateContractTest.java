package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for ChunkCandidate and StructureAwareChunkingResult.
 *
 * <p>These tests lock down the field contract and default behavior before implementation.
 * Following TDD: write test first, run failure, then implement.
 */
class ChunkCandidateContractTest {

    @Nested
    @DisplayName("ChunkCandidate contract")
    class ChunkCandidateContract {

        @Test
        @DisplayName("should use immutable empty collections and typed contexts by default")
        void build_DefaultChunkCandidate_UsesImmutableEmptyCollections() {
            ChunkCandidate candidate = ChunkCandidate.builder().build();

            assertNotNull(candidate.blockOrders());
            assertNotNull(candidate.blockTypes());
            assertNotNull(candidate.sectionPath());
            assertNotNull(candidate.pageNumbers());
            assertNotNull(candidate.documentContext());
            assertNotNull(candidate.semanticContext());
            assertNotNull(candidate.derivationContext());

            assertTrue(candidate.blockOrders().isEmpty());
            assertTrue(candidate.blockTypes().isEmpty());
            assertTrue(candidate.sectionPath().isEmpty());
            assertTrue(candidate.pageNumbers().isEmpty());
            assertEquals("", candidate.documentContext().sourceType());
            assertEquals("", candidate.documentContext().fileName());
            assertEquals("", candidate.documentContext().preprocessingVersion());
            assertNull(candidate.documentContext().documentTitle());

            assertThrows(UnsupportedOperationException.class, () ->
                    candidate.blockOrders().add(1));
            assertThrows(UnsupportedOperationException.class, () ->
                    candidate.blockTypes().add("PARAGRAPH"));
            assertThrows(UnsupportedOperationException.class, () ->
                    candidate.sectionPath().add("section"));
            assertThrows(UnsupportedOperationException.class, () ->
                    candidate.pageNumbers().add(1));
        }

        @Test
        @DisplayName("should separate displayText from embeddingText")
        void build_ChunkCandidate_SeparatesDisplayTextFromEmbeddingText() {
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .bodyText("Original body text")
                    .displayText("Display text for users")
                    .embeddingText("Context prefix\n\nDisplay text for users")
                    .build();

            assertEquals(0, candidate.chunkIndex());
            assertEquals("Original body text", candidate.bodyText());
            assertEquals("Display text for users", candidate.displayText());
            assertEquals("Context prefix\n\nDisplay text for users", candidate.embeddingText());

            // displayText and embeddingText can be different
            assertNotEquals(candidate.displayText(), candidate.embeddingText());
        }

        @Test
        @DisplayName("should carry semantic and document context for chunk lineage")
        void build_ChunkCandidate_CarriesBlockMetadata() {
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(1)
                    .blockOrders(List.of(5, 6, 7))
                    .blockTypes(List.of("PARAGRAPH", "PARAGRAPH", "LIST_ITEM"))
                    .sectionPath(List.of("Chapter 1", "Section 1.2"))
                    .pageNumbers(List.of(3, 4))
                    .tokenCountEstimate(150)
                    .documentContext(ChunkDocumentContext.builder()
                            .sourceType("MARKDOWN")
                            .documentTitle("Doc")
                            .fileName("test.md")
                            .preprocessingVersion("v1")
                            .build())
                    .build();

            assertEquals(1, candidate.chunkIndex());
            assertEquals(List.of(5, 6, 7), candidate.blockOrders());
            assertEquals(List.of("PARAGRAPH", "PARAGRAPH", "LIST_ITEM"), candidate.blockTypes());
            assertEquals(List.of("Chapter 1", "Section 1.2"), candidate.sectionPath());
            assertEquals(List.of(3, 4), candidate.pageNumbers());
            assertEquals(150, candidate.tokenCountEstimate());
            assertEquals("MARKDOWN", candidate.documentContext().sourceType());
            assertEquals("Doc", candidate.documentContext().documentTitle());
        }
    }

    @Nested
    @DisplayName("StructureAwareChunkingResult contract")
    class StructureAwareChunkingResultContract {

        @Test
        @DisplayName("should carry candidate list and summary metrics")
        void build_ChunkingResult_CarriesCandidatesAndSummaryMetrics() {
            ChunkCandidate candidate1 = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Text 1")
                    .embeddingText("Embed 1")
                    .build();

            ChunkCandidate candidate2 = ChunkCandidate.builder()
                    .chunkIndex(1)
                    .displayText("Text 2")
                    .embeddingText("Embed 2")
                    .build();

            StructureAwareChunkingResult result = StructureAwareChunkingResult.builder()
                    .candidates(List.of(candidate1, candidate2))
                    .retainedBlockCount(10)
                    .droppedBlockCount(2)
                    .totalDisplayChars(100)
                    .totalEmbeddingChars(150)
                    .build();

            assertEquals(2, result.candidates().size());
            assertEquals(10, result.retainedBlockCount());
            assertEquals(2, result.droppedBlockCount());
            assertEquals(100, result.totalDisplayChars());
            assertEquals(150, result.totalEmbeddingChars());
        }

        @Test
        @DisplayName("should default candidates to empty list")
        void build_DefaultChunkingResult_UsesEmptyCandidatesList() {
            StructureAwareChunkingResult result = StructureAwareChunkingResult.builder().build();

            assertNotNull(result.candidates());
            assertTrue(result.candidates().isEmpty());
            assertEquals(0, result.retainedBlockCount());
            assertEquals(0, result.droppedBlockCount());
            assertEquals(0, result.totalDisplayChars());
            assertEquals(0, result.totalEmbeddingChars());
        }
    }

    @Nested
    @DisplayName("ChunkingProperties configuration binding")
    class ChunkingPropertiesContract {

        @Test
        @DisplayName("should have safe rollout defaults")
        void defaults_ShouldHaveSafeRolloutDefaults() {
            ChunkingProperties props = new ChunkingProperties();

            // Feature flags default to off for safe rollout
            assertFalse(props.isStructureAwareEnabled(), "structureAwareEnabled should default to false");
            assertTrue(props.isShadowReportEnabled(), "shadowReportEnabled can default to true");

            // Thresholds should have reasonable defaults
            assertTrue(props.getParagraphSoftChars() > 0, "paragraphSoftChars should have positive default");
            assertTrue(props.getParagraphHardChars() > props.getParagraphSoftChars(),
                    "paragraphHardChars should be greater than paragraphSoftChars");

            // Table thresholds: trigger vs max should be distinct
            assertTrue(props.getTableSplitTriggerRows() > 0, "tableSplitTriggerRows should have positive default");
            assertTrue(props.getTableMaxRowsPerChunk() > 0, "tableMaxRowsPerChunk should have positive default");
            assertTrue(props.getTableSplitTriggerRows() > props.getTableMaxRowsPerChunk(),
                    "tableSplitTriggerRows should be greater than tableMaxRowsPerChunk");

            // Code thresholds: trigger vs max should be distinct
            assertTrue(props.getCodeSplitTriggerLines() > 0, "codeSplitTriggerLines should have positive default");
            assertTrue(props.getCodeMaxLinesPerChunk() > 0, "codeMaxLinesPerChunk should have positive default");
            assertTrue(props.getCodeSplitTriggerLines() > props.getCodeMaxLinesPerChunk(),
                    "codeSplitTriggerLines should be greater than codeMaxLinesPerChunk");

            // Parent context limits
            assertTrue(props.getParentContextMaxLevels() > 0, "parentContextMaxLevels should have positive default");
            assertTrue(props.getParentContextMaxChars() > 0, "parentContextMaxChars should have positive default");
        }
    }
}
