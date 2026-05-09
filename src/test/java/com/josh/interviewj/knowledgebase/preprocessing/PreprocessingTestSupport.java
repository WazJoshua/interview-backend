package com.josh.interviewj.knowledgebase.preprocessing;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;

import java.util.List;
import java.util.Map;

public final class PreprocessingTestSupport {

    private PreprocessingTestSupport() {
    }

    public static ParsedBlock parsedBlock(ParsedBlockType type, String text, int order) {
        return ParsedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .build();
    }

    public static NormalizedBlock block(NormalizedBlockType type, String text, int order) {
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .metadata(Map.of())
                .build();
    }

    public static NormalizedBlock block(NormalizedBlockType type, String text, int order, Integer pageNumber) {
        return NormalizedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .pageNumber(pageNumber)
                .metadata(Map.of())
                .build();
    }

    public static PreprocessingContext context(NormalizedBlock... blocks) {
        return context(DocumentSourceType.PDF, "test.pdf", blocks);
    }

    public static PreprocessingContext context(DocumentSourceType sourceType, NormalizedBlock... blocks) {
        return context(sourceType, "test." + sourceType.name().toLowerCase(), blocks);
    }

    public static PreprocessingContext context(DocumentSourceType sourceType, String fileName, NormalizedBlock... blocks) {
        ParsedDocument parsedDocument = ParsedDocument.builder()
                .sourceType(sourceType)
                .fileName(fileName)
                .blocks(List.of(blocks).stream()
                        .map(block -> ParsedBlock.builder()
                                .type(ParsedBlockType.PARAGRAPH)
                                .text(block.text())
                                .order(block.order())
                                .pageNumber(block.pageNumber())
                                .sectionPath(block.sectionPath())
                                .metadata(block.metadata())
                                .build())
                        .toList())
                .build();
        return PreprocessingContext.builder()
                .parsedDocument(parsedDocument)
                .workingBlocks(List.of(blocks))
                .warnings(List.of())
                .documentMetadata(Map.of())
                .stepMetrics(Map.of())
                .qualitySignals(Map.of())
                .profile(new DocumentPreprocessingProperties.ProfileProperties(false))
                .build();
    }
}
