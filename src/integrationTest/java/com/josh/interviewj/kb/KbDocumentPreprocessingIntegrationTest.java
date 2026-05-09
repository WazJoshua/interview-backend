package com.josh.interviewj.kb;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalProtectionRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalScoreRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalStrongDropRules;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocxDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.parser.MarkdownDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.PdfDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DefaultDocumentPreprocessingPipeline;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentCleaningStep;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.preprocessing.steps.BuildQualitySummaryStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DeduplicateBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DropLowSignalBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.EnrichSectionPathStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeCharactersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeDocumentMetadataStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeWhitespaceStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveEmptyBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemovePageNumbersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveRepeatedHeadersFootersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveTocFragmentsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RepairBrokenLinesAndParagraphsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.SplitPayloadHeavyBlocksStep;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbDocumentPreprocessingIntegrationTest {

    private final DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
    private final FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
    private final DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
            new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
            properties,
            createSteps()
    );

    @Test
    void markdownGoldenSample_CompletesPreprocessingSuccessfully() throws Exception {
        Path file = Files.createTempFile("kb-markdown-", ".md");
        Files.writeString(file, """
                # API Error Codes

                Intro paragraph for KB preprocessing validation.

                - AUTH_001 invalid token

                ```bash
                ./gradlew test --tests "Sample"
                ```

                Appendix sample
                value_a=0000000000000000000000000000000000
                value_b=1111111111111111111111111111111111
                value_c=2222222222222222222222222222222222
                value_d=3333333333333333333333333333333333
                value_e=4444444444444444444444444444444444
                value_f=5555555555555555555555555555555555
                value_g=6666666666666666666666666666666666
                value_h=7777777777777777777777777777777777
                """);

        assertGoldenSample(file, "text/markdown", "sample.md");
    }

    @Test
    void docxGoldenSample_CompletesPreprocessingSuccessfully() throws Exception {
        Path file = Files.createTempFile("kb-docx-", ".docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Docx KB Sample");

            XWPFParagraph body = document.createParagraph();
            body.createRun().setText(
                    "This DOCX sample keeps title, body, and short technical fragments while also providing enough " +
                            "natural language content to pass the first-phase quality threshold for normalized text " +
                            "length. The paragraph intentionally includes descriptive prose about APIs, ingestion, " +
                            "and preprocessing so the adapter has a realistic chunking input."
            );

            XWPFParagraph list = document.createParagraph();
            list.createRun().setText("- GET /api/v1/knowledge-bases");

            try (OutputStream outputStream = Files.newOutputStream(file)) {
                document.write(outputStream);
            }
        }

        assertGoldenSample(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "sample.docx");
    }

    @Test
    void pdfGoldenSample_CompletesPreprocessingSuccessfully() throws Exception {
        Path file = Files.createTempFile("kb-pdf-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(PDType1Font.HELVETICA, 12);
                float y = 720;
                String[] lines = {
                        "KB PDF Sample",
                        "This line validates PDF parsing for preprocessing with enough descriptive content.",
                        "The second paragraph explains how document preprocessing keeps important short technical blocks.",
                        "It also makes sure the PDF sample exceeds the minimum normalized text length quality gate.",
                        "AUTH_001 invalid token",
                        "./gradlew integrationTest",
                        "Appendix sample",
                        "value_a=0000000000000000000000000000000000"
                };
                for (String line : lines) {
                    stream.beginText();
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(file.toFile());
        }

        assertGoldenSample(file, "application/pdf", "sample.pdf");
    }

    private void assertGoldenSample(Path file, String fileType, String fileName) {
        NormalizedDocument normalizedDocument = pipeline.preprocess(file, fileType, fileName);
        FixedSizeChunkingInput input = adapter.adapt(normalizedDocument);

        // Basic quality checks
        assertFalse(normalizedDocument.blocks().isEmpty(), "Document should have blocks");
        assertFalse(input.normalizedTextForChunking().isBlank(), "Should have text for chunking");
        assertFalse(input.retainedSegments().isEmpty(), "Should have retained segments");

        // Verify section path normalization step was applied
        for (var block : normalizedDocument.blocks()) {
            assertTrue(block.metadata().containsKey("sectionDepth"),
                    "Block should have sectionDepth metadata from EnrichSectionPathStep");
            assertTrue(block.metadata().containsKey("sectionPathConfidence"),
                    "Block should have sectionPathConfidence metadata from EnrichSectionPathStep");
            assertTrue(block.metadata().containsKey("sectionPathNormalized"),
                    "Block should have sectionPathNormalized metadata from EnrichSectionPathStep");

            // Verify confidence range
            Double confidence = (Double) block.metadata().get("sectionPathConfidence");
            assertNotNull(confidence, "sectionPathConfidence should not be null");
            assertTrue(confidence >= 0.0 && confidence <= 1.0,
                    "sectionPathConfidence should be in range [0.0, 1.0]");
        }
    }

    private List<DocumentCleaningStep> createSteps() {
        LowSignalBlockRuleEngine ruleEngine = new LowSignalBlockRuleEngine(
                properties,
                new LowSignalProtectionRules(),
                new LowSignalStrongDropRules(),
                new LowSignalScoreRules()
        );
        return List.of(
                new RemoveEmptyBlocksStep(),
                new NormalizeCharactersStep(),
                new NormalizeWhitespaceStep(),
                new RemoveRepeatedHeadersFootersStep(),
                new RemovePageNumbersStep(),
                new RemoveTocFragmentsStep(),
                new RepairBrokenLinesAndParagraphsStep(),
                new SplitPayloadHeavyBlocksStep(),
                new DropLowSignalBlocksStep(ruleEngine),
                new DeduplicateBlocksStep(),
                new EnrichSectionPathStep(properties),  // Section path normalization - single source of truth
                new NormalizeDocumentMetadataStep(),
                new BuildQualitySummaryStep(properties)
        );
    }
}
