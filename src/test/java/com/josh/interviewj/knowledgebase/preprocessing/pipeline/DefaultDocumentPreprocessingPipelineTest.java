package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalProtectionRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalScoreRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalStrongDropRules;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.parser.MarkdownDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.PdfDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocxDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.steps.BuildQualitySummaryStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DeduplicateBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DropLowSignalBlocksStep;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDocumentPreprocessingPipelineTest {

    @Test
    void getSteps_ExposesFixedOrderForTesting() {
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                properties,
                List.of(
                        new RemoveEmptyBlocksStep(),
                        new NormalizeCharactersStep(),
                        new NormalizeWhitespaceStep(),
                        new RemoveRepeatedHeadersFootersStep(),
                        new RemovePageNumbersStep(),
                        new RemoveTocFragmentsStep(),
                        new RepairBrokenLinesAndParagraphsStep(),
                        new SplitPayloadHeavyBlocksStep(),
                        new DropLowSignalBlocksStep(new LowSignalBlockRuleEngine(
                                properties,
                                new LowSignalProtectionRules(),
                                new LowSignalStrongDropRules(),
                                new LowSignalScoreRules()
                        )),
                        new DeduplicateBlocksStep(),
                        new NormalizeDocumentMetadataStep(),
                        new BuildQualitySummaryStep(properties)
                )
        );

        assertEquals(
                List.of(
                        "RemoveEmptyBlocks",
                        "NormalizeCharacters",
                        "NormalizeWhitespace",
                        "RemoveRepeatedHeadersFooters",
                        "RemovePageNumbers",
                        "RemoveTocFragments",
                        "RepairBrokenLinesAndParagraphs",
                        "SplitPayloadHeavyBlocks",
                        "DropLowSignalBlocks",
                        "DeduplicateBlocks",
                        "NormalizeDocumentMetadata",
                        "BuildQualitySummary"
                ),
                pipeline.getSteps().stream().map(DocumentCleaningStep::getName).toList()
        );
    }

    @Test
    void preprocess_AppendixPayloadFixture_ProducesSoftDeindexPayloadTrace() throws Exception {
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
                properties,
                List.of(
                        new RemoveEmptyBlocksStep(),
                        new NormalizeCharactersStep(),
                        new NormalizeWhitespaceStep(),
                        new RemoveRepeatedHeadersFootersStep(),
                        new RemovePageNumbersStep(),
                        new RemoveTocFragmentsStep(),
                        new RepairBrokenLinesAndParagraphsStep(),
                        new SplitPayloadHeavyBlocksStep(),
                        new DropLowSignalBlocksStep(new LowSignalBlockRuleEngine(
                                properties,
                                new LowSignalProtectionRules(),
                                new LowSignalStrongDropRules(),
                                new LowSignalScoreRules()
                        )),
                        new DeduplicateBlocksStep(),
                        new NormalizeDocumentMetadataStep(),
                        new BuildQualitySummaryStep(properties)
                )
        );

        Path fixture = Path.of(getClass().getClassLoader()
                .getResource("knowledgebase/evaluation/noise-reduction/fixtures/appendix-payload.md")
                .toURI());

        var normalizedDocument = pipeline.preprocess(fixture, "text/markdown", "appendix-payload.md");

        assertTrue(
                normalizedDocument.blocks().stream()
                        .anyMatch(block -> "SOFT_DEINDEX".equals(block.metadata().get("retrievalDisposition"))),
                normalizedDocument.blocks()::toString
        );
        assertTrue(
                normalizedDocument.reviewProjection().blocks().stream()
                        .anyMatch(block -> block.disposition().name().equals("REVIEW_ONLY")),
                normalizedDocument.reviewProjection().blocks()::toString
        );
    }

    @Test
    void preprocess_ApiReferenceExamplesFixture_DoesNotSoftDeindexMainBodySamples() throws Exception {
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
                properties,
                List.of(
                        new RemoveEmptyBlocksStep(),
                        new NormalizeCharactersStep(),
                        new NormalizeWhitespaceStep(),
                        new RemoveRepeatedHeadersFootersStep(),
                        new RemovePageNumbersStep(),
                        new RemoveTocFragmentsStep(),
                        new RepairBrokenLinesAndParagraphsStep(),
                        new SplitPayloadHeavyBlocksStep(),
                        new DropLowSignalBlocksStep(new LowSignalBlockRuleEngine(
                                properties,
                                new LowSignalProtectionRules(),
                                new LowSignalStrongDropRules(),
                                new LowSignalScoreRules()
                        )),
                        new DeduplicateBlocksStep(),
                        new NormalizeDocumentMetadataStep(),
                        new BuildQualitySummaryStep(properties)
                )
        );
        Path fixture = Files.createTempFile("api-reference-examples-", ".md");
        Files.writeString(fixture, """
                # API Reference

                ## Examples

                This example shows how to call the query API.

                token=abcdef0123456789
                payload={"question":"How do I retry?"}
                status=200
                body={"result":"ok"}
                """);

        var normalizedDocument = pipeline.preprocess(fixture, "text/markdown", "api-reference-examples.md");

        assertFalse(
                normalizedDocument.blocks().stream()
                        .anyMatch(block -> "SOFT_DEINDEX".equals(block.metadata().get("retrievalDisposition"))),
                normalizedDocument.blocks()::toString
        );
    }

    @Test
    void preprocess_PdfAppendixPayloadAcrossBlocks_StillSoftDeindexesPayload() throws Exception {
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
                properties,
                List.of(
                        new RemoveEmptyBlocksStep(),
                        new NormalizeCharactersStep(),
                        new NormalizeWhitespaceStep(),
                        new RemoveRepeatedHeadersFootersStep(),
                        new RemovePageNumbersStep(),
                        new RemoveTocFragmentsStep(),
                        new RepairBrokenLinesAndParagraphsStep(),
                        new SplitPayloadHeavyBlocksStep(),
                        new DropLowSignalBlocksStep(new LowSignalBlockRuleEngine(
                                properties,
                                new LowSignalProtectionRules(),
                                new LowSignalStrongDropRules(),
                                new LowSignalScoreRules()
                        )),
                        new DeduplicateBlocksStep(),
                        new NormalizeDocumentMetadataStep(),
                        new BuildQualitySummaryStep(properties)
                )
        );
        Path fixture = Files.createTempFile("appendix-payload-pdf-", ".pdf");
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
                        "value_a=0000000000000000000000000000000000",
                        "value_b=1111111111111111111111111111111111",
                        "value_c=2222222222222222222222222222222222",
                        "value_d=3333333333333333333333333333333333"
                }) {
                    stream.beginText();
                    stream.newLineAtOffset(72, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 18;
                }
            }
            document.save(fixture.toFile());
        }

        var normalizedDocument = pipeline.preprocess(fixture, "application/pdf", "appendix-payload.pdf");

        assertTrue(
                normalizedDocument.blocks().stream()
                        .anyMatch(block -> "payload".equals(block.metadata().get("payloadSplitRole"))
                                && "SOFT_DEINDEX".equals(block.metadata().get("retrievalDisposition"))),
                normalizedDocument.blocks()::toString
        );
    }

    @Test
    void preprocess_AppendixPayloadFixture_DropProfileStillKeepsReviewProjection() throws Exception {
        // Aggressive profile with dropAppendixSamples=true
        DocumentPreprocessingProperties.ProfileProperties aggressiveProfile =
                DocumentPreprocessingProperties.ProfileProperties.builder()
                        .dropAppendixSamples(true)
                        .build();
        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        properties.getProfiles().put("default", aggressiveProfile);

        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
                properties,
                List.of(
                        new RemoveEmptyBlocksStep(),
                        new NormalizeCharactersStep(),
                        new NormalizeWhitespaceStep(),
                        new RemoveRepeatedHeadersFootersStep(),
                        new RemovePageNumbersStep(),
                        new RemoveTocFragmentsStep(),
                        new RepairBrokenLinesAndParagraphsStep(),
                        new SplitPayloadHeavyBlocksStep(),
                        new DropLowSignalBlocksStep(new LowSignalBlockRuleEngine(
                                properties,
                                new LowSignalProtectionRules(),
                                new LowSignalStrongDropRules(),
                                new LowSignalScoreRules()
                        )),
                        new DeduplicateBlocksStep(),
                        new NormalizeDocumentMetadataStep(),
                        new BuildQualitySummaryStep(properties)
                )
        );

        Path fixture = Path.of(getClass().getClassLoader()
                .getResource("knowledgebase/evaluation/noise-reduction/fixtures/appendix-payload.md")
                .toURI());

        var normalizedDocument = pipeline.preprocess(fixture, "text/markdown", "appendix-payload.md");

        // Payload should not appear in blocks with DROP disposition (since we're using aggressive profile)
        // The aggressive profile drops appendix samples, so they should not be in working blocks
        assertFalse(
                normalizedDocument.blocks().stream()
                        .anyMatch(block -> "DROP".equals(block.metadata().get("retrievalDisposition"))),
                "Dropped appendix payload should not be in normalizedDocument.blocks()"
        );

        // But should still be in reviewProjection for audit artifact
        assertTrue(
                normalizedDocument.reviewProjection().blocks().stream()
                        .anyMatch(block -> block.disposition().name().equals("REVIEW_ONLY")),
                "Dropped appendix payload should be retained in reviewProjection for audit artifact"
        );
    }
}
