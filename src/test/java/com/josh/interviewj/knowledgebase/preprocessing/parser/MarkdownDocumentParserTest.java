package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void parse_UsesAstToPreserveStructuredBlocks() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".md");
        Files.writeString(file, """
                # Title

                Paragraph text.

                - first item

                > quoted text

                ```bash
                ./gradlew test
                ```

                | A | B |
                |---|---|
                | 1 | 2 |
                """);

        ParsedDocument document = parser.parse(file, "text/markdown", "sample.md");

        assertEquals("Title", document.title());
        assertTrue(document.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.TITLE));
        assertTrue(document.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.LIST_ITEM));
        assertTrue(document.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.QUOTE));
        assertTrue(document.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.CODE));
        assertTrue(document.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.TABLE));
    }

    @Test
    void parse_DoesNotDuplicateHeadingAndParagraphLeafText() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".md");
        Files.writeString(file, """
                # Title

                Paragraph text.

                - first item
                """);

        ParsedDocument document = parser.parse(file, "text/markdown", "sample.md");

        assertEquals(3, document.blocks().size());
        assertEquals(1, document.blocks().stream().filter(block -> block.type() == ParsedBlockType.TITLE).count());
        assertEquals(1, document.blocks().stream().filter(block -> block.type() == ParsedBlockType.PARAGRAPH).count());
        assertEquals(1, document.blocks().stream().filter(block -> block.type() == ParsedBlockType.LIST_ITEM).count());
        assertEquals(
                0,
                document.blocks().stream()
                        .filter(block -> block.type() == ParsedBlockType.UNKNOWN)
                        .filter(block -> block.text().equals("Title") || block.text().equals("Paragraph text."))
                        .count()
        );
    }

    @Test
    void parse_MarkdownPreservesHeadingPathForSampleSections() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".md");
        Files.writeString(file, """
                # KB Guide

                ## 附录

                ### Samples

                This appendix explains the sample payload.

                token=abcdef0123456789
                """);

        ParsedDocument document = parser.parse(file, "text/markdown", "sample.md");

        assertTrue(document.blocks().stream()
                .filter(block -> block.type() == ParsedBlockType.PARAGRAPH)
                .anyMatch(block -> block.sectionPath().equals(java.util.List.of("KB Guide", "附录", "Samples"))));
    }
}
