package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StructureAwareChunkingService.
 *
 * <p>Following TDD: write test first, run failure, then implement.
 */
class StructureAwareChunkingServiceTest {

    private StructureAwareChunkingService service;
    private ChunkingProperties chunkingProperties;

    @BeforeEach
    void setUp() {
        chunkingProperties = new ChunkingProperties();
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        properties.setChunking(chunkingProperties);
        service = new StructureAwareChunkingService(
                properties,
                new ChunkCandidateFactory(new ParentContextTemplateBuilder(properties))
        );
    }

    @Nested
    @DisplayName("Paragraph chunking")
    class ParagraphChunking {

        @Test
        @DisplayName("should split paragraphs on section boundary")
        void chunk_ParagraphsSplitOnSectionBoundary() {
            // Given: Paragraphs in different sections
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph in section A", 0, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Another paragraph in section A", 1, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph in section B", 2, List.of("Section B")),
                            block(NormalizedBlockType.PARAGRAPH, "Another paragraph in section B", 3, List.of("Section B"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should have separate chunks for each section
            assertEquals(2, result.candidates().size(), "Should have 2 chunks for 2 sections");

            // First chunk should have section A content
            assertEquals(List.of("Section A"), result.candidates().get(0).sectionPath());
            assertTrue(result.candidates().get(0).displayText().contains("section A"));

            // Second chunk should have section B content
            assertEquals(List.of("Section B"), result.candidates().get(1).sectionPath());
            assertTrue(result.candidates().get(1).displayText().contains("section B"));
        }

        @Test
        @DisplayName("should aggregate paragraphs within same section")
        void chunk_ParagraphsWithinSameSection_Aggregated() {
            // Given: Multiple short paragraphs in same section
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "First paragraph.", 0, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Second paragraph.", 1, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Third paragraph.", 2, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should aggregate into single chunk
            assertEquals(1, result.candidates().size());
            assertTrue(result.candidates().get(0).displayText().contains("First paragraph."));
            assertTrue(result.candidates().get(0).displayText().contains("Second paragraph."));
            assertTrue(result.candidates().get(0).displayText().contains("Third paragraph."));
        }
    }

    @Nested
    @DisplayName("List item chunking")
    class ListItemChunking {

        @Test
        @DisplayName("should keep list items grouped with short lead sentence")
        void chunk_ListItemsStayGroupedWithShortLeadSentence() {
            // Given: List items with lead sentence (short paragraph < 100 chars, same section)
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Here are the items:", 0, List.of("Section A")),
                            block(NormalizedBlockType.LIST_ITEM, "Item 1", 1, List.of("Section A")),
                            block(NormalizedBlockType.LIST_ITEM, "Item 2", 2, List.of("Section A")),
                            block(NormalizedBlockType.LIST_ITEM, "Item 3", 3, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should have 1 chunk (lead sentence + list items grouped together)
            assertEquals(1, result.candidates().size(), "Should have 1 chunk with lead sentence and list items");

            // The chunk should contain lead sentence + all list items
            ChunkCandidate chunk = result.candidates().get(0);
            assertEquals(4, chunk.blockOrders().size(), "Chunk should have lead sentence + 3 list items");
            assertTrue(chunk.blockTypes().contains("PARAGRAPH"), "Chunk should contain lead sentence (PARAGRAPH)");
            assertTrue(chunk.blockTypes().contains("LIST_ITEM"), "Chunk should contain LIST_ITEM");
        }

        @Test
        @DisplayName("should not mix list items with non-lead regular paragraphs")
        void chunk_ListItemsNotMixedWithNonLeadParagraphs() {
            // Given: A long paragraph (> 100 chars) followed by list items
            // Long paragraph should be its own chunk, not merged with list
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            // Long paragraph (> 100 chars) - should NOT be merged with list
                            block(NormalizedBlockType.PARAGRAPH, "This is a very long regular paragraph that exceeds one hundred characters and should not be merged with the following list items because it is too long to be considered a lead sentence.", 0, List.of("Section A")),
                            block(NormalizedBlockType.LIST_ITEM, "List item 1", 1, List.of("Section A")),
                            block(NormalizedBlockType.LIST_ITEM, "List item 2", 2, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Another paragraph", 3, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Long paragraph should be separate from list chunk
            assertTrue(result.candidates().size() >= 2, "Should have at least 2 chunks");

            // Find the chunk with list items
            ChunkCandidate listChunk = result.candidates().stream()
                    .filter(c -> c.blockTypes().contains("LIST_ITEM"))
                    .findFirst()
                    .orElseThrow();

            // List chunk should not contain the long paragraph
            assertFalse(listChunk.blockOrders().contains(0),
                    "List chunk should not contain the long paragraph (order 0)");
        }
    }

    @Nested
    @DisplayName("Table chunking")
    class TableChunking {

        @Test
        @DisplayName("should keep table as separate chunk")
        void chunk_TableAsSeparateChunk() {
            // Given: A table block
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph before table", 0, List.of("Section A")),
                            block(NormalizedBlockType.TABLE, "Header1 | Header2\nRow1 | Row2", 1, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph after table", 2, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Table should be in its own chunk
            ChunkCandidate tableChunk = result.candidates().stream()
                    .filter(c -> c.blockTypes().contains("TABLE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, tableChunk.blockOrders().size(), "Table should be in its own chunk");
        }

        @Test
        @DisplayName("should split large tables by rows")
        void chunk_LargeTable_SplitsByRows() {
            // Given: A large table exceeding trigger threshold
            StringBuilder tableBuilder = new StringBuilder("Header1 | Header2 | Header3\n");
            for (int i = 1; i <= 40; i++) {  // 40 rows, exceeds trigger of 30
                tableBuilder.append("Row").append(i).append(" | Data").append(i).append(" | Value").append(i).append("\n");
            }

            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.TABLE, tableBuilder.toString(), 0, List.of("Section A"), Map.of("rows", 40))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should have multiple table chunks
            assertTrue(result.candidates().size() > 1, "Large table should be split into multiple chunks");
            // All chunks should be table type
            for (ChunkCandidate candidate : result.candidates()) {
                assertTrue(candidate.blockTypes().contains("TABLE"));
            }
        }
    }

    @Nested
    @DisplayName("Code chunking")
    class CodeChunking {

        @Test
        @DisplayName("should keep code as separate chunk")
        void chunk_CodeAsSeparateChunk() {
            // Given: A code block
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph before code", 0, List.of("Section A")),
                            block(NormalizedBlockType.CODE, "public void test() {\n  System.out.println(\"Hello\");\n}", 1, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph after code", 2, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Code should be in its own chunk
            ChunkCandidate codeChunk = result.candidates().stream()
                    .filter(c -> c.blockTypes().contains("CODE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, codeChunk.blockOrders().size(), "Code should be in its own chunk");
        }

        @Test
        @DisplayName("should split large code blocks by lines")
        void chunk_LargeCode_SplitsByLines() {
            // Given: A large code block exceeding trigger threshold
            StringBuilder codeBuilder = new StringBuilder();
            for (int i = 1; i <= 150; i++) {  // 150 lines, exceeds trigger of 120
                codeBuilder.append("// Line ").append(i).append("\n");
            }

            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.CODE, codeBuilder.toString(), 0, List.of("Section A"), Map.of("language", "java"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should have multiple code chunks
            assertTrue(result.candidates().size() > 1, "Large code should be split into multiple chunks");
            // All chunks should be code type
            for (ChunkCandidate candidate : result.candidates()) {
                assertTrue(candidate.blockTypes().contains("CODE"));
            }
        }
    }

    @Nested
    @DisplayName("Quote and unknown handling")
    class QuoteAndUnknownHandling {

        @Test
        @DisplayName("should use paragraph fallback for quote but preserve block type")
        void chunk_QuoteAndUnknown_UseSafeParagraphFallback() {
            // Given: Quote and unknown blocks
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.QUOTE, "A famous quote", 0, List.of("Section A")),
                            block(NormalizedBlockType.UNKNOWN, "Unknown content", 1, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Should have chunks with preserved block types
            assertEquals(1, result.candidates().size());
            assertTrue(result.candidates().get(0).blockTypes().contains("QUOTE"));
            assertTrue(result.candidates().get(0).blockTypes().contains("UNKNOWN"));
        }
    }

    @Nested
    @DisplayName("Header and footer exclusion")
    class HeaderFooterExclusion {

        @Test
        @DisplayName("should exclude header and footer from candidate set")
        void chunk_HeaderAndFooter_ExcludedFromCandidateSet() {
            // Given: Document with header and footer
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.DOCX)
                    .fileName("test.docx")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.HEADER, "Page Header", 0, List.of()),
                            block(NormalizedBlockType.PARAGRAPH, "Main content", 1, List.of("Section A")),
                            block(NormalizedBlockType.FOOTER, "Page Footer", 2, List.of())
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Header and footer should not be in chunks
            for (ChunkCandidate candidate : result.candidates()) {
                assertFalse(candidate.blockTypes().contains("HEADER"), "Header should be excluded");
                assertFalse(candidate.blockTypes().contains("FOOTER"), "Footer should be excluded");
            }
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("should produce stable chunk indexes for same input")
        void chunk_SameInputProducesStableChunkIndexes() {
            // Given: A document
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph 1", 0, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph 2", 1, List.of("Section A")),
                            block(NormalizedBlockType.PARAGRAPH, "Paragraph 3", 2, List.of("Section B"))
                    ))
                    .build();

            // When: Chunk twice
            StructureAwareChunkingResult result1 = service.chunk(document);
            StructureAwareChunkingResult result2 = service.chunk(document);

            // Then: Results should be identical
            assertEquals(result1.candidates().size(), result2.candidates().size());
            for (int i = 0; i < result1.candidates().size(); i++) {
                assertEquals(result1.candidates().get(i).chunkIndex(), result2.candidates().get(i).chunkIndex());
                assertEquals(result1.candidates().get(i).displayText(), result2.candidates().get(i).displayText());
                assertEquals(result1.candidates().get(i).sectionPath(), result2.candidates().get(i).sectionPath());
            }
        }
    }

    @Nested
    @DisplayName("Metadata enrichment")
    class MetadataEnrichment {

        @Test
        @DisplayName("should include required metadata fields")
        void chunk_IncludesRequiredMetadataFields() {
            // Given
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Content", 0, List.of("Section A"), 1)
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then
            ChunkCandidate candidate = result.candidates().get(0);
            assertEquals("Test Document", candidate.metadata().get("documentTitle"));
            assertEquals(List.of("Section A"), candidate.sectionPath());
            assertTrue(candidate.blockTypes().contains("PARAGRAPH"));
            assertEquals(List.of(1), candidate.pageNumbers());
            assertEquals("MARKDOWN", candidate.metadata().get("sourceType"));
            assertEquals("STRUCTURE_AWARE_V1", candidate.metadata().get("chunkVersion"));
        }

        @Test
        @DisplayName("should tolerate missing title and empty optional collections")
        void chunk_TitleMissingAndOptionalCollections_DoesNotThrow() {
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName("untitled.pdf")
                    .title(null)
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Body content", 0, List.of()),
                            block(NormalizedBlockType.PARAGRAPH, "More body content", 1, List.of())
                    ))
                    .build();

            StructureAwareChunkingResult result = assertDoesNotThrow(() -> service.chunk(document));

            assertFalse(result.candidates().isEmpty(), "Should still create chunk candidates");
            for (ChunkCandidate candidate : result.candidates()) {
                assertNotNull(candidate.blockOrders());
                assertNotNull(candidate.blockTypes());
                assertNotNull(candidate.sectionPath());
                assertNotNull(candidate.pageNumbers());
                assertTrue(candidate.blockOrders().stream().noneMatch(java.util.Objects::isNull));
                assertTrue(candidate.blockTypes().stream().noneMatch(java.util.Objects::isNull));
                assertTrue(candidate.sectionPath().stream().noneMatch(java.util.Objects::isNull));
                assertTrue(candidate.pageNumbers().stream().noneMatch(java.util.Objects::isNull));
            }
        }
    }

    @Nested
    @DisplayName("Retrieval disposition filtering")
    class RetrievalDispositionFiltering {

        @Test
        @DisplayName("should exclude DROP and SOFT_DEINDEX blocks")
        void chunk_DropAndSoftDeindexBlocks_Excluded() {
            // Given: Blocks with different dispositions
            NormalizedDocument document = NormalizedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .title("Test Document")
                    .blocks(List.of(
                            block(NormalizedBlockType.PARAGRAPH, "Keep this", 0, List.of("Section A")),
                            createBlockWithDisposition(NormalizedBlockType.PARAGRAPH, "Drop this", 1, List.of("Section A"), RetrievalDisposition.DROP),
                            createBlockWithDisposition(NormalizedBlockType.PARAGRAPH, "Soft deindex this", 2, List.of("Section A"), RetrievalDisposition.SOFT_DEINDEX),
                            block(NormalizedBlockType.PARAGRAPH, "Also keep this", 3, List.of("Section A"))
                    ))
                    .build();

            // When
            StructureAwareChunkingResult result = service.chunk(document);

            // Then: Only KEEP blocks should be in chunks
            assertTrue(result.candidates().size() >= 1);
            for (ChunkCandidate candidate : result.candidates()) {
                assertFalse(candidate.displayText().contains("Drop this"));
                assertFalse(candidate.displayText().contains("Soft deindex this"));
            }
        }
    }

    // Helper methods
    private NormalizedBlock block(NormalizedBlockType type, String text, int order, List<String> sectionPath) {
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .sectionPath(sectionPath)
                .metadata(Map.of("sectionDepth", sectionPath.size(), "sectionPathConfidence", 1.0))
                .build();
    }

    private NormalizedBlock block(NormalizedBlockType type, String text, int order, List<String> sectionPath, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new java.util.HashMap<>(Map.of("sectionDepth", sectionPath.size(), "sectionPathConfidence", 1.0));
        metadata.putAll(extraMetadata);
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .sectionPath(sectionPath)
                .metadata(metadata)
                .build();
    }

    private NormalizedBlock block(NormalizedBlockType type, String text, int order, List<String> sectionPath, Integer pageNumber) {
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .pageNumber(pageNumber)
                .sectionPath(sectionPath)
                .metadata(Map.of("sectionDepth", sectionPath.size(), "sectionPathConfidence", 1.0))
                .build();
    }

    private NormalizedBlock createBlockWithDisposition(NormalizedBlockType type, String text, int order, List<String> sectionPath, RetrievalDisposition disposition) {
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .sectionPath(sectionPath)
                .metadata(Map.of(
                        "sectionDepth", sectionPath.size(),
                        "sectionPathConfidence", 1.0,
                        "retrievalDisposition", disposition.name()
                ))
                .build();
    }
}
