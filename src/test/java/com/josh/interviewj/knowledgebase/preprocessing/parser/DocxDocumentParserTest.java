package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void parse_PreservesHeadingListAndTableBlocks() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Docx Title");

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("Paragraph body");

            XWPFParagraph listParagraph = document.createParagraph();
            listParagraph.createRun().setText("- list item");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A");
            table.getRow(0).getCell(1).setText("B");
            table.getRow(1).getCell(0).setText("1");
            table.getRow(1).getCell(1).setText("2");

            try (OutputStream outputStream = Files.newOutputStream(file)) {
                document.write(outputStream);
            }
        }

        ParsedDocument parsedDocument = parser.parse(
                file,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "sample.docx"
        );

        assertEquals("Docx Title", parsedDocument.title());
        assertTrue(parsedDocument.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.TITLE));
        assertTrue(parsedDocument.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.PARAGRAPH));
        assertTrue(parsedDocument.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.LIST_ITEM));
        assertTrue(parsedDocument.blocks().stream().anyMatch(block -> block.type() == ParsedBlockType.TABLE));
    }

    @Test
    void parse_DocxPreservesHeadingStyleMetadataForAppendixDetection() throws Exception {
        Path file = Files.createTempFile("kb-preprocessing-", ".docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("KB Guide");

            XWPFParagraph appendix = document.createParagraph();
            appendix.setStyle("Heading2");
            appendix.createRun().setText("附录");

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("token=abcdef0123456789");

            try (OutputStream outputStream = Files.newOutputStream(file)) {
                document.write(outputStream);
            }
        }

        ParsedDocument parsedDocument = parser.parse(
                file,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "sample.docx"
        );

        assertTrue(parsedDocument.blocks().stream()
                .filter(block -> block.text().equals("附录"))
                .anyMatch(block -> "heading".equals(block.metadata().get("styleRole"))));
        assertTrue(parsedDocument.blocks().stream()
                .filter(block -> block.text().equals("token=abcdef0123456789"))
                .anyMatch(block -> block.sectionPath().equals(java.util.List.of("KB Guide", "附录"))));
    }
}
