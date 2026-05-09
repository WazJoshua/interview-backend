package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void parse_PreservesPageAwareBlocksAndEmitsLayoutWarnings() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(PDType1Font.HELVETICA, 12);
                float y = 700;
                for (int index = 0; index < 10; index++) {
                    float x = index % 2 == 0 ? 72 : 260;
                    stream.beginText();
                    stream.newLineAtOffset(x, y);
                    stream.showText("Line " + index + " column " + (index % 2));
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(file.toFile());
        }

        ParsedDocument parsedDocument = parser.parse(file, "application/pdf", "sample.pdf");

        assertFalse(parsedDocument.blocks().isEmpty());
        assertNotNull(parsedDocument.blocks().get(0).pageNumber());
        assertTrue(parsedDocument.warnings().stream().anyMatch(warning -> warning.code().startsWith("POSSIBLE_")));
    }

    @Test
    void parse_PdfPreservesSignalsNeededForConservativePayloadSplitting() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(PDType1Font.HELVETICA, 12);
                float y = 700;
                for (String line : new String[]{
                        "Appendix sample",
                        "This payload is for reference only.",
                        "token=abcdef0123456789"
                }) {
                    stream.beginText();
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(file.toFile());
        }

        ParsedDocument parsedDocument = parser.parse(file, "application/pdf", "sample.pdf");

        assertTrue(parsedDocument.blocks().stream()
                .filter(block -> block.metadata().containsKey("lineTexts"))
                .anyMatch(block -> ((java.util.List<?>) block.metadata().get("lineTexts")).size() >= 2));
    }

    @Test
    void parse_PdfCarriesAppendixSectionContextToFollowingBlocks() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(PDType1Font.HELVETICA, 12);
                float y = 700;
                for (String line : new String[]{
                        "Appendix",
                        "This payload is for reference only.",
                        "token=abcdef0123456789",
                        "payload={\"a\":1,\"b\":2}",
                        "value_a=0000000000000000000000000000000000"
                }) {
                    stream.beginText();
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(file.toFile());
        }

        ParsedDocument parsedDocument = parser.parse(file, "application/pdf", "sample.pdf");

        assertTrue(parsedDocument.blocks().stream().anyMatch(block -> block.text().contains("token=abcdef0123456789")));
        assertTrue(parsedDocument.blocks().stream()
                .filter(block -> block.text().contains("token=abcdef0123456789"))
                .allMatch(block -> !block.sectionPath().isEmpty()));
        assertEquals(
                List.of("Appendix"),
                parsedDocument.blocks().stream()
                        .filter(block -> block.text().contains("token=abcdef0123456789"))
                        .findFirst()
                        .orElseThrow()
                        .sectionPath()
        );
    }
}
