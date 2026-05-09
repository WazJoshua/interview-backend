package com.josh.interviewj.knowledgebase.preprocessing.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserRegistryTest {

    private final DocumentParserRegistry registry = new DocumentParserRegistry(
            List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())
    );

    @Test
    void supports_ChoosesOnlyStructuredFormats() {
        assertTrue(registry.supports("application/pdf", "sample.pdf"));
        assertTrue(registry.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "sample.docx"));
        assertTrue(registry.supports("text/markdown", "sample.md"));

        assertFalse(registry.supports("text/plain", "sample.txt"));
        assertFalse(registry.supports("text/html", "sample.html"));
        assertFalse(registry.supports("application/msword", "sample.doc"));
    }
}
