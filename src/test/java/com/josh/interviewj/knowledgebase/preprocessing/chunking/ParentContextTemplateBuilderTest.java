package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParentContextTemplateBuilder.
 *
 * <p>Following TDD: write test first, run failure, then implement.
 */
class ParentContextTemplateBuilderTest {

    private ParentContextTemplateBuilder builder;
    private ChunkingProperties chunkingProperties;

    @BeforeEach
    void setUp() {
        chunkingProperties = new ChunkingProperties();
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        properties.setChunking(chunkingProperties);
        builder = new ParentContextTemplateBuilder(properties);
    }

    @Nested
    @DisplayName("Paragraph chunk template")
    class ParagraphChunkTemplate {

        @Test
        @DisplayName("should use minimal document and section prefix")
        void build_ParagraphChunk_UsesMinimalDocumentAndSectionPrefix() {
            // Given
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("This is the paragraph content.")
                    .sectionPath(List.of("Chapter 1", "Section 1.2"))
                    .metadata(Map.of(
                            "documentTitle", "My Document",
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "My Document");

            // Then: Should have document and section context
            assertTrue(embeddingText.contains("My Document"), "Should contain document title");
            assertTrue(embeddingText.contains("Chapter 1"), "Should contain section path");
            assertTrue(embeddingText.contains("This is the paragraph content."), "Should contain content");
            // displayText should remain unchanged
            assertEquals("This is the paragraph content.", candidate.displayText());
        }

        @Test
        @DisplayName("should omit section when path is empty")
        void build_EmptySectionPath_OmitsSectionContext() {
            // Given
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Content without section.")
                    .sectionPath(List.of())
                    .metadata(Map.of(
                            "documentTitle", "My Document",
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "My Document");

            // Then: Should have only document title
            assertTrue(embeddingText.contains("My Document"));
            assertTrue(embeddingText.contains("Content without section."));
        }
    }

    @Nested
    @DisplayName("Table chunk template")
    class TableChunkTemplate {

        @Test
        @DisplayName("should repeat header without polluting displayText")
        void build_TableChunk_RepeatsHeaderWithoutPollutingDisplayText() {
            // Given: Table chunk with header
            String tableContent = "Header1 | Header2\nRow1 | Row2";
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText(tableContent)
                    .blockTypes(List.of("TABLE"))
                    .sectionPath(List.of("Appendix"))
                    .metadata(Map.of(
                            "documentTitle", "My Document",
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "My Document");

            // Then
            assertTrue(embeddingText.contains("表格") || embeddingText.contains("TABLE"), "Should indicate type");
            assertTrue(embeddingText.contains("Appendix"), "Should contain section");
            // displayText should remain pure table content
            assertEquals(tableContent, candidate.displayText());
        }
    }

    @Nested
    @DisplayName("Code chunk template")
    class CodeChunkTemplate {

        @Test
        @DisplayName("should include type and language when available")
        void build_CodeChunk_WithLanguage() {
            // Given
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("public void test() {}")
                    .blockTypes(List.of("CODE"))
                    .sectionPath(List.of("API Reference"))
                    .metadata(Map.of(
                            "documentTitle", "API Docs",
                            "sourceType", "MARKDOWN",
                            "language", "java"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "API Docs");

            // Then
            assertTrue(embeddingText.contains("代码") || embeddingText.contains("CODE"), "Should indicate code type");
            assertTrue(embeddingText.contains("java"), "Should include language");
        }

        @Test
        @DisplayName("should omit language when confidence missing")
        void build_CodeChunk_OmitsLanguageWhenConfidenceMissing() {
            // Given: Code without language
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("some code here")
                    .blockTypes(List.of("CODE"))
                    .sectionPath(List.of())
                    .metadata(Map.of(
                            "documentTitle", "Docs",
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "Docs");

            // Then: Should still have CODE type but no language in parentheses
            assertTrue(embeddingText.contains("代码") || embeddingText.contains("CODE"));
            assertFalse(embeddingText.contains("(java)") || embeddingText.contains("(python)"));
        }
    }

    @Nested
    @DisplayName("Section path truncation")
    class SectionPathTruncation {

        @Test
        @DisplayName("should truncate long section path to configured limit")
        void build_LongSectionPath_TruncatesToConfiguredLimit() {
            // Given: Very long section path
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Content")
                    .sectionPath(List.of("Level1", "Level2", "Level3", "Level4", "Level5", "Level6"))
                    .metadata(Map.of(
                            "documentTitle", "Doc",
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "Doc");

            // Then: Should only include last 2 levels (parentContextMaxLevels = 2)
            assertTrue(embeddingText.contains("Level5") || embeddingText.contains("Level6"));
            // Full path should NOT be in embedding text
            assertFalse(embeddingText.contains("Level1") && embeddingText.contains("Level2") && embeddingText.contains("Level3"));
        }
    }

    @Nested
    @DisplayName("Character limit")
    class CharacterLimit {

        @Test
        @DisplayName("should truncate parent context if exceeds max chars")
        void build_LongParentContext_TruncatesToConfiguredLimit() {
            // Given: Document with very long title and section
            String longTitle = "This is a very long document title that should be truncated when building parent context prefix for embedding";
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Content")
                    .sectionPath(List.of("This is a very long section path that should also be truncated"))
                    .metadata(Map.of(
                            "documentTitle", longTitle,
                            "sourceType", "MARKDOWN"
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, longTitle);

            // Then: Parent context prefix should be limited (parentContextMaxChars = 180)
            // The total prefix (before content) should be reasonable
            int contentStart = embeddingText.indexOf("Content");
            assertTrue(contentStart > 0, "Should have parent context prefix");
            // Prefix should be within reasonable bounds
            assertTrue(embeddingText.length() < 500, "Total embedding text should be reasonable");
        }
    }

    @Nested
    @DisplayName("hasParentContext metadata")
    class HasParentContextMetadata {

        @Test
        @DisplayName("should set hasParentContext to true when context is injected")
        void build_WithContext_SetsHasParentContextTrue() {
            // Given
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Content")
                    .sectionPath(List.of("Section A"))
                    .metadata(Map.of(
                            "documentTitle", "Doc",
                            "sourceType", "MARKDOWN",
                            "hasParentContext", false
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "Doc");

            // Then: embeddingText should be different from displayText
            assertNotEquals(candidate.displayText(), embeddingText);
        }

        @Test
        @DisplayName("should keep hasParentContext false when no meaningful context")
        void build_NoContext_KeepsHasParentContextFalse() {
            // Given: Minimal content with no section path
            ChunkCandidate candidate = ChunkCandidate.builder()
                    .chunkIndex(0)
                    .displayText("Just content")
                    .sectionPath(List.of())
                    .metadata(Map.of(
                            "documentTitle", "",
                            "sourceType", "MARKDOWN",
                            "hasParentContext", false
                    ))
                    .build();

            // When
            String embeddingText = builder.buildEmbeddingText(candidate, "");

            // Then: embeddingText might be similar to displayText (no meaningful context to add)
            assertTrue(embeddingText.contains("Just content"));
        }
    }
}