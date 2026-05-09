package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EnrichSectionPathStep.
 *
 * <p>Following TDD: write test first, run failure, then implement.
 */
class EnrichSectionPathStepTest {

    private EnrichSectionPathStep step;
    private ChunkingProperties chunkingProperties;

    @BeforeEach
    void setUp() {
        chunkingProperties = new ChunkingProperties();
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        properties.setChunking(chunkingProperties);
        step = new EnrichSectionPathStep(properties);
    }

    @Nested
    @DisplayName("Markdown section path normalization")
    class MarkdownSectionPath {

        @Test
        @DisplayName("should produce stable section path with H1 as title, H2/H3 inheritance")
        void apply_MarkdownHeadings_ProducesStableSectionPath() {
            // Given: Markdown document with H1 (title), H2, H3 hierarchy
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .blocks(List.of(
                            block(ParsedBlockType.TITLE, "Document Title", 0, List.of(), Map.of("headingLevel", 1)),
                            block(ParsedBlockType.PARAGRAPH, "Intro text", 1, List.of("Document Title"), Map.of()),
                            block(ParsedBlockType.HEADING, "Section 1", 2, List.of("Document Title"), Map.of("headingLevel", 2)),
                            block(ParsedBlockType.PARAGRAPH, "Section 1 content", 3, List.of("Document Title", "Section 1"), Map.of()),
                            block(ParsedBlockType.HEADING, "Section 1.1", 4, List.of("Document Title", "Section 1"), Map.of("headingLevel", 3)),
                            block(ParsedBlockType.PARAGRAPH, "Section 1.1 content", 5, List.of("Document Title", "Section 1", "Section 1.1"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: sectionPath should be stable and inherit correctly
            List<NormalizedBlock> blocks = result.workingBlocks();

            // TITLE block
            assertEquals(List.of(), blocks.get(0).sectionPath(), "TITLE should have empty sectionPath");
            assertEquals(0, blocks.get(0).metadata().get("sectionDepth"));
            assertEquals(1.0, blocks.get(0).metadata().get("sectionPathConfidence"));

            // Paragraph after TITLE
            assertEquals(List.of("Document Title"), blocks.get(1).sectionPath());
            assertEquals(1, blocks.get(1).metadata().get("sectionDepth"));
            assertEquals(1.0, blocks.get(1).metadata().get("sectionPathConfidence"));

            // H2 Section 1
            assertEquals(List.of("Document Title"), blocks.get(2).sectionPath());

            // Paragraph under Section 1
            assertEquals(List.of("Document Title", "Section 1"), blocks.get(3).sectionPath());
            assertEquals(2, blocks.get(3).metadata().get("sectionDepth"));

            // H3 Section 1.1
            assertEquals(List.of("Document Title", "Section 1"), blocks.get(4).sectionPath());

            // Paragraph under Section 1.1
            assertEquals(List.of("Document Title", "Section 1", "Section 1.1"), blocks.get(5).sectionPath());
            assertEquals(3, blocks.get(5).metadata().get("sectionDepth"));
        }
    }

    @Nested
    @DisplayName("DOCX heading gap handling")
    class DocxHeadingGap {

        @Test
        @DisplayName("should add warning for heading level gap without synthesizing virtual heading")
        void apply_DocxHeadingGap_AddsWarningWithoutSynthesizingVirtualHeading() {
            // Given: DOCX with H1 followed by H3 (gap in H2)
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.DOCX)
                    .fileName("test.docx")
                    .blocks(List.of(
                            block(ParsedBlockType.TITLE, "Document Title", 0, List.of(), Map.of("headingLevel", 1)),
                            block(ParsedBlockType.PARAGRAPH, "Intro text", 1, List.of("Document Title"), Map.of()),
                            block(ParsedBlockType.HEADING, "Section 1", 2, List.of("Document Title"), Map.of("headingLevel", 2)),
                            block(ParsedBlockType.PARAGRAPH, "Section 1 content", 3, List.of("Document Title", "Section 1"), Map.of()),
                            // Jump from H2 directly to H4 (gap): heading's path is parent level
                            block(ParsedBlockType.HEADING, "Section 1.0.0.1", 4, List.of("Document Title", "Section 1"), Map.of("headingLevel", 4)),
                            // Paragraph under H4 should have path including the heading
                            block(ParsedBlockType.PARAGRAPH, "Sub-sub content", 5, List.of("Document Title", "Section 1", "Section 1.0.0.1"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: should have heading level gap warning
            List<DocumentWarning> warnings = result.warnings();
            assertTrue(warnings.stream().anyMatch(w -> "HEADING_LEVEL_GAP".equals(w.code())),
                    "Should have HEADING_LEVEL_GAP warning");

            // And: section path should be stable (not synthesizing virtual heading)
            List<NormalizedBlock> blocks = result.workingBlocks();
            // The H4 heading block should have parent-level path (as per parser design)
            assertEquals(List.of("Document Title", "Section 1"), blocks.get(4).sectionPath(),
                    "H4 heading should have parent-level path");
            // The paragraph after H4 should include the heading in its path
            assertEquals(List.of("Document Title", "Section 1", "Section 1.0.0.1"), blocks.get(5).sectionPath(),
                    "Paragraph after H4 should include heading in path");
        }
    }

    @Nested
    @DisplayName("PDF weak section path support")
    class PdfWeakSectionPath {

        @Test
        @DisplayName("should allow empty section path for weak heading signals")
        void apply_PdfWeakHeading_AllowsEmptySectionPath() {
            // Given: PDF with only weak heading signals
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName("test.pdf")
                    .blocks(List.of(
                            block(ParsedBlockType.PARAGRAPH, "Some paragraph without heading", 0, List.of(), Map.of()),
                            block(ParsedBlockType.PARAGRAPH, "Another paragraph", 1, List.of(), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: sectionPath can be empty for PDF with weak signals
            List<NormalizedBlock> blocks = result.workingBlocks();
            assertEquals(List.of(), blocks.get(0).sectionPath());
            assertEquals(0, blocks.get(0).metadata().get("sectionDepth"));
            // Confidence should be low for PDF weak support
            assertTrue((Double) blocks.get(0).metadata().get("sectionPathConfidence") < 1.0,
                    "PDF with weak heading should have confidence < 1.0");
        }

        @Test
        @DisplayName("should preserve only shallow path for PDF with weak heading")
        void apply_PdfWeakHeading_PreservesOnlyShallowPath() {
            // Given: PDF with weak appendix heading
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName("test.pdf")
                    .blocks(List.of(
                            block(ParsedBlockType.PARAGRAPH, "Main content", 0, List.of(), Map.of()),
                            block(ParsedBlockType.PARAGRAPH, "Appendix content", 1, List.of("Appendix A"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: should preserve shallow path but with lower confidence
            List<NormalizedBlock> blocks = result.workingBlocks();
            assertEquals(List.of(), blocks.get(0).sectionPath());
            assertEquals(List.of("Appendix A"), blocks.get(1).sectionPath());
        }

        @Test
        @DisplayName("should truncate deep section path for PDF when pdfWeakSectionPathEnabled")
        void apply_PdfDeepPath_TruncatesToMaxDepth() {
            // Given: PDF with deep section path (more than 2 levels)
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName("test.pdf")
                    .blocks(List.of(
                            block(ParsedBlockType.PARAGRAPH, "Deep content", 0,
                                    List.of("Chapter 1", "Section 1.1", "Subsection 1.1.1", "Detail"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: should truncate to max depth (2 levels)
            List<NormalizedBlock> blocks = result.workingBlocks();
            assertEquals(List.of("Chapter 1", "Section 1.1"), blocks.get(0).sectionPath(),
                    "PDF deep path should be truncated to max depth");
            assertEquals(true, blocks.get(0).metadata().get("sectionPathNormalized"),
                    "sectionPathNormalized should be true when path is modified");
        }

        @Test
        @DisplayName("should disable truncation when pdfWeakSectionPathEnabled is false")
        void apply_PdfDeepPath_DisabledKeepsOriginalPath() {
            // Given: PDF with deep section path, but truncation disabled
            chunkingProperties.setPdfWeakSectionPathEnabled(false);
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName("test.pdf")
                    .blocks(List.of(
                            block(ParsedBlockType.PARAGRAPH, "Deep content", 0,
                                    List.of("Chapter 1", "Section 1.1", "Subsection 1.1.1", "Detail"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: should preserve original path when truncation disabled
            List<NormalizedBlock> blocks = result.workingBlocks();
            assertEquals(List.of("Chapter 1", "Section 1.1", "Subsection 1.1.1", "Detail"), blocks.get(0).sectionPath(),
                    "PDF path should be preserved when truncation disabled");
            assertEquals(false, blocks.get(0).metadata().get("sectionPathNormalized"),
                    "sectionPathNormalized should be false when path is not modified");
        }
    }

    @Nested
    @DisplayName("Section path metadata keys")
    class SectionPathMetadata {

        @Test
        @DisplayName("should use fixed metadata keys for section depth and confidence")
        void apply_SectionPathMetadata_UsesFixedMetadataKeys() {
            // Given
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .blocks(List.of(
                            block(ParsedBlockType.TITLE, "Title", 0, List.of(), Map.of("headingLevel", 1)),
                            block(ParsedBlockType.PARAGRAPH, "Content", 1, List.of("Title"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then: metadata should have fixed keys
            NormalizedBlock paragraph = result.workingBlocks().get(1);
            assertTrue(paragraph.metadata().containsKey("sectionDepth"));
            assertTrue(paragraph.metadata().containsKey("sectionPathConfidence"));
            assertTrue(paragraph.metadata().containsKey("sectionPathNormalized"));

            // Types should be correct
            assertInstanceOf(Integer.class, paragraph.metadata().get("sectionDepth"));
            assertInstanceOf(Double.class, paragraph.metadata().get("sectionPathConfidence"));
            assertInstanceOf(Boolean.class, paragraph.metadata().get("sectionPathNormalized"));
        }
    }

    @Nested
    @DisplayName("HEADING block should drive section path but not have deep path itself")
    class HeadingBlockBehavior {

        @Test
        @DisplayName("should have heading block drive subsequent blocks but not have deep path")
        void apply_HeadingBlock_DrivesSubsequentBlocks() {
            // Given: A heading followed by content
            ParsedDocument parsed = ParsedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName("test.md")
                    .blocks(List.of(
                            block(ParsedBlockType.HEADING, "Section A", 0, List.of(), Map.of("headingLevel", 2)),
                            block(ParsedBlockType.PARAGRAPH, "Content under A", 1, List.of("Section A"), Map.of())
                    ))
                    .build();

            PreprocessingContext context = PreprocessingContext.fromParsedDocument(
                    parsed,
                    DocumentPreprocessingProperties.ProfileProperties.builder().build()
            );

            // When
            PreprocessingContext result = step.apply(context);

            // Then
            List<NormalizedBlock> blocks = result.workingBlocks();
            // HEADING itself should have path pointing to parent level
            assertEquals(List.of(), blocks.get(0).sectionPath(), "HEADING should have parent level path");
            // Paragraph should include the heading in its path
            assertEquals(List.of("Section A"), blocks.get(1).sectionPath(), "Paragraph should include heading in path");
        }
    }

    // Helper
    private ParsedBlock block(ParsedBlockType type, String text, int order, List<String> sectionPath, Map<String, Object> metadata) {
        return ParsedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .sectionPath(sectionPath)
                .metadata(metadata)
                .build();
    }
}