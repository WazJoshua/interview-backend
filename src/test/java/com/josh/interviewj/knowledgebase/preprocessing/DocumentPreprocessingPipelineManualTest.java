package com.josh.interviewj.knowledgebase.preprocessing;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalProtectionRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalScoreRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalStrongDropRules;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentQualitySummary;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
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
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeCharactersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeDocumentMetadataStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeWhitespaceStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveEmptyBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemovePageNumbersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveRepeatedHeadersFootersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveTocFragmentsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RepairBrokenLinesAndParagraphsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.SplitPayloadHeavyBlocksStep;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentPreprocessingPipelineManualTest {

    /**
     * 手动改成项目根目录下的相对路径，例如：
     * uploads/kb/a3a12158-2d8f-422a-960e-587accba8bad.pdf
     * 也兼容绝对路径。
     */
    private static final String SAMPLE_FILE_PATH = "uploads/kb/a3a12158-2d8f-422a-960e-587accba8bad.pdf";
    /**
     * 若要更激进地过滤 appendix sample，可改为 true。
     */
    private static final boolean DROP_APPENDIX_SAMPLES = false;

    @Test
    void preprocessSpecifiedFile_PrintNormalizedDocumentToConsole() {
        assumeTrue(!SAMPLE_FILE_PATH.isBlank(), "请先在 SAMPLE_FILE_PATH 中填写要测试的相对路径或绝对路径。");

        Path sampleFile = resolveSampleFile(SAMPLE_FILE_PATH);
        System.out.println("sampleFile = " + sampleFile);
        System.out.println(Files.exists(sampleFile));
        assumeTrue(Files.exists(sampleFile), "指定文件不存在: " + sampleFile);

        String fileName = sampleFile.getFileName().toString();
        String fileType = detectFileType(fileName);

        DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
        properties.getProfiles().put(
                "default",
                DocumentPreprocessingProperties.ProfileProperties.builder()
                        .dropAppendixSamples(DROP_APPENDIX_SAMPLES)
                        .build()
        );

        DefaultDocumentPreprocessingPipeline pipeline = new DefaultDocumentPreprocessingPipeline(
                new DocumentParserRegistry(List.of(
                        new PdfDocumentParser(),
                        new DocxDocumentParser(),
                        new MarkdownDocumentParser()
                )),
                properties,
                createSteps(properties)
        );
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();

        NormalizedDocument normalizedDocument = pipeline.preprocess(sampleFile, fileType, fileName);
        FixedSizeChunkingInput chunkingInput = adapter.adapt(normalizedDocument);

        printDocumentSummary(sampleFile, fileType, normalizedDocument, chunkingInput);

        assertFalse(normalizedDocument.blocks().isEmpty(), "预处理后不应没有 block。");
        assertFalse(chunkingInput.normalizedTextForChunking().isBlank(), "适配给 chunking 的文本不应为空。");
    }

    private List<DocumentCleaningStep> createSteps(DocumentPreprocessingProperties properties) {
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
                new NormalizeDocumentMetadataStep(),
                new BuildQualitySummaryStep(properties)
        );
    }

    private void printDocumentSummary(
            Path sampleFile,
            String fileType,
            NormalizedDocument normalizedDocument,
            FixedSizeChunkingInput chunkingInput
    ) {
        System.out.println("========== KB Preprocessing Manual Test ==========");
        System.out.println("file=" + sampleFile.toAbsolutePath());
        System.out.println("fileType=" + fileType);
        System.out.println("sourceType=" + normalizedDocument.sourceType());
        System.out.println("title=" + normalizedDocument.title());
        System.out.println("metadata=" + normalizedDocument.metadata());

        DocumentQualitySummary qualitySummary = normalizedDocument.qualitySummary();
        Map<String, Object> qualitySummaryView = new LinkedHashMap<>();
        qualitySummaryView.put("originalBlockCount", qualitySummary.originalBlockCount());
        qualitySummaryView.put("normalizedBlockCount", qualitySummary.normalizedBlockCount());
        qualitySummaryView.put("removedEmptyBlockCount", qualitySummary.removedEmptyBlockCount());
        qualitySummaryView.put("removedHeaderFooterCount", qualitySummary.removedHeaderFooterCount());
        qualitySummaryView.put("removedPageNumberCount", qualitySummary.removedPageNumberCount());
        qualitySummaryView.put("removedTocFragmentCount", qualitySummary.removedTocFragmentCount());
        qualitySummaryView.put("droppedLowSignalBlockCount", qualitySummary.droppedLowSignalBlockCount());
        qualitySummaryView.put("warnedLowSignalBlockCount", qualitySummary.warnedLowSignalBlockCount());
        qualitySummaryView.put("deduplicatedBlockCount", qualitySummary.deduplicatedBlockCount());
        qualitySummaryView.put("warningCount", qualitySummary.warningCount());
        qualitySummaryView.put("hasStructuralWarnings", qualitySummary.hasStructuralWarnings());
        qualitySummaryView.put("hasReadabilityWarnings", qualitySummary.hasReadabilityWarnings());
        qualitySummaryView.put("fitForMainIndex", qualitySummary.fitForMainIndex());
        qualitySummaryView.put("metrics", qualitySummary.metrics());
        System.out.println("qualitySummary=" + qualitySummaryView);

        System.out.println("warnings:");
        if (normalizedDocument.warnings().isEmpty()) {
            System.out.println("  <none>");
        } else {
            for (DocumentWarning warning : normalizedDocument.warnings()) {
                System.out.println("  - code=" + warning.code()
                        + ", category=" + warning.category()
                        + ", message=" + warning.message()
                        + ", metadata=" + warning.metadata());
            }
        }

        System.out.println("blocks:");
        for (NormalizedBlock block : normalizedDocument.blocks()) {
            System.out.println("  - order=" + block.order()
                    + ", type=" + block.type()
                    + ", page=" + block.pageNumber()
                    + ", sectionPath=" + block.sectionPath()
                    + ", metadata=" + block.metadata());
            System.out.println("    text=" + block.text());
        }

        System.out.println("retainedSegments:");
        for (FixedSizeChunkingInput.RetainedSegment segment : chunkingInput.retainedSegments()) {
            System.out.println("  - blockOrder=" + segment.blockOrder()
                    + ", blockType=" + segment.blockType()
                    + ", pageNumber=" + segment.pageNumber()
                    + ", startOffset=" + segment.startOffset()
                    + ", endOffset=" + segment.endOffset()
                    + ", qualityFlags=" + segment.qualityFlags()
                    + ", preprocessingWarnings=" + segment.preprocessingWarnings());
        }

        System.out.println("normalizedTextForChunking.length=" + chunkingInput.normalizedTextForChunking().length());
        System.out.println("normalizedTextForChunking:");
        System.out.println(chunkingInput.normalizedTextForChunking());
        System.out.println("==================================================");
    }

    private String detectFileType(String fileName) {
        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (normalizedName.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (normalizedName.endsWith(".md") || normalizedName.endsWith(".markdown")) {
            return "text/markdown";
        }
        throw new IllegalArgumentException("当前 manual test 仅支持 pdf/docx/md 文件: " + fileName);
    }

    private Path resolveSampleFile(String sampleFilePath) {
        Path path = Path.of(sampleFilePath.replace("~", System.getProperty("user.home")));
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Path.of("").toAbsolutePath().resolve(path).normalize();
    }
}
