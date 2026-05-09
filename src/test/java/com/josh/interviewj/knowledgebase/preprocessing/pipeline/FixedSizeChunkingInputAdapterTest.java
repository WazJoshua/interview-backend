package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentQualitySummary;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedSizeChunkingInputAdapterTest {

    @Test
    void adapt_ConcatenatesRetainedBlocksAndProducesStableSegments() {
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
        NormalizedDocument document = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .blocks(List.of(
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("chunk A")
                                .order(7)
                                .pageNumber(2)
                                .metadata(Map.of("dropLowSignalDecision", "WARN"))
                                .build(),
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("chunk B")
                                .order(8)
                                .pageNumber(3)
                                .metadata(Map.of("dropLowSignalDecision", "KEEP"))
                                .build()
                ))
                .warnings(List.of(DocumentWarning.builder()
                        .code("POSSIBLE_MULTI_COLUMN_LAYOUT")
                        .category(DocumentWarningCategory.STRUCTURAL)
                        .message("layout")
                        .build()))
                .qualitySummary(DocumentQualitySummary.empty())
                .build();

        FixedSizeChunkingInput input = adapter.adapt(document);

        assertEquals("chunk B", input.normalizedTextForChunking());
        assertEquals(1, input.retainedSegments().size());
        assertTrue(input.retainedSegments().get(0).qualityFlags().contains("HAS_STRUCTURAL_WARNING"));
        assertEquals(List.of("POSSIBLE_MULTI_COLUMN_LAYOUT"), input.retainedSegments().get(0).preprocessingWarnings());
        assertFalse(input.retainedSegments().get(0).endOffset() <= input.retainedSegments().get(0).startOffset());
    }

    @Test
    void adapt_IgnoresWarningsWithNullCode() {
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
        NormalizedDocument document = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .blocks(List.of(
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("chunk A")
                                .order(1)
                                .pageNumber(1)
                                .metadata(Map.of())
                                .build()
                ))
                .warnings(List.of(
                        DocumentWarning.builder()
                                .code(null)
                                .category(DocumentWarningCategory.STRUCTURAL)
                                .message("missing code")
                                .build(),
                        DocumentWarning.builder()
                                .code("POSSIBLE_MULTI_COLUMN_LAYOUT")
                                .category(DocumentWarningCategory.STRUCTURAL)
                                .message("layout")
                                .build()
                ))
                .qualitySummary(DocumentQualitySummary.empty())
                .build();

        FixedSizeChunkingInput input = assertDoesNotThrow(() -> adapter.adapt(document));

        assertEquals(List.of("POSSIBLE_MULTI_COLUMN_LAYOUT"), input.retainedSegments().get(0).preprocessingWarnings());
    }

    @Test
    void adapt_PropagatesCanonicalDispositionAndReasonCodes() {
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
        NormalizedDocument document = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .blocks(List.of(
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("chunk A")
                                .order(7)
                                .pageNumber(2)
                                .metadata(Map.of(
                                        "retrievalDisposition", "SOFT_DEINDEX",
                                        "retrievalDispositionReasonCodes", List.of("APPENDIX_SAMPLE_PAYLOAD"),
                                        "retrievalDispositionEvidence", List.of("reason:APPENDIX_SAMPLE_PAYLOAD")
                                ))
                                .build(),
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.CODE)
                                .text("./gradlew test")
                                .order(8)
                                .pageNumber(3)
                                .metadata(Map.of(
                                        "retrievalDisposition", "PROTECT",
                                        "retrievalDispositionReasonCodes", List.of("PROTECTED_TECHNICAL_ANCHOR"),
                                        "retrievalDispositionEvidence", List.of("reason:PROTECTED_TECHNICAL_ANCHOR")
                                ))
                                .build()
                ))
                .warnings(List.of(DocumentWarning.builder()
                        .code("POSSIBLE_MULTI_COLUMN_LAYOUT")
                        .category(DocumentWarningCategory.STRUCTURAL)
                        .message("layout")
                        .build()))
                .qualitySummary(DocumentQualitySummary.empty())
                .build();

        FixedSizeChunkingInput input = adapter.adapt(document);

        assertEquals(1, input.retainedSegments().size());
        assertEquals("./gradlew test", input.normalizedTextForChunking());
        assertEquals(RetrievalDisposition.PROTECT, input.retainedSegments().get(0).retrievalDisposition());
        assertTrue(input.retainedSegments().get(0).qualityFlags().contains("HAS_PROTECTED_ANCHOR"));
        assertEquals(
                List.of(RetrievalDispositionReasonCode.PROTECTED_TECHNICAL_ANCHOR),
                input.retainedSegments().get(0).retrievalDispositionReasonCodes()
        );
    }

    @Test
    void adapt_ReviewOnlyBlocksAreNotIncludedInChunkingInput() {
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
        NormalizedDocument document = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.MARKDOWN)
                .fileName("sample.md")
                .blocks(List.of(
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("appendix payload")
                                .order(1)
                                .pageNumber(2)
                                .metadata(Map.of("retrievalDisposition", "SOFT_DEINDEX"))
                                .build(),
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("main body")
                                .order(2)
                                .pageNumber(2)
                                .metadata(Map.of("retrievalDisposition", "KEEP"))
                                .build()
                ))
                .qualitySummary(DocumentQualitySummary.empty())
                .build();

        FixedSizeChunkingInput input = adapter.adapt(document);

        assertEquals("main body", input.normalizedTextForChunking());
        assertEquals(1, input.retainedSegments().size());
        assertEquals(2, input.retainedSegments().get(0).blockOrder());
    }

    @Test
    void adapt_InvalidCanonicalMetadata_FallsBackInsteadOfThrowing() {
        FixedSizeChunkingInputAdapter adapter = new FixedSizeChunkingInputAdapter();
        NormalizedDocument document = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.MARKDOWN)
                .fileName("sample.md")
                .blocks(List.of(
                        NormalizedBlock.builder()
                                .type(NormalizedBlockType.PARAGRAPH)
                                .text("payload")
                                .order(1)
                                .metadata(Map.of(
                                        "retrievalDisposition", "UNKNOWN_VALUE",
                                        "retrievalDispositionReasonCodes", List.of("NOT_A_REAL_REASON")
                                ))
                                .build()
                ))
                .warnings(List.of())
                .qualitySummary(DocumentQualitySummary.empty())
                .build();

        FixedSizeChunkingInput input = assertDoesNotThrow(() -> adapter.adapt(document));

        assertEquals(RetrievalDisposition.KEEP, input.retainedSegments().get(0).retrievalDisposition());
        assertEquals(
                List.of(RetrievalDispositionReasonCode.COMPATIBILITY_FALLBACK),
                input.retainedSegments().get(0).retrievalDispositionReasonCodes()
        );
    }
}
