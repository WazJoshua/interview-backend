package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewProjection;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewTextBlock;
import lombok.Builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record PreprocessingContext(
        ParsedDocument parsedDocument,
        List<NormalizedBlock> workingBlocks,
        List<DocumentWarning> warnings,
        Map<String, Object> documentMetadata,
        Map<String, StepMetric> stepMetrics,
        Map<String, Object> qualitySignals,
        ReviewProjection reviewProjection,
        DocumentPreprocessingProperties.ProfileProperties profile
) {

    public PreprocessingContext {
        workingBlocks = workingBlocks == null ? new ArrayList<>() : new ArrayList<>(workingBlocks);
        warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        documentMetadata = documentMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(documentMetadata);
        stepMetrics = stepMetrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stepMetrics);
        qualitySignals = qualitySignals == null ? new LinkedHashMap<>() : new LinkedHashMap<>(qualitySignals);
        reviewProjection = reviewProjection == null ? ReviewProjection.builder().build() : reviewProjection;
    }

    public static PreprocessingContext fromParsedDocument(
            ParsedDocument parsedDocument,
            DocumentPreprocessingProperties.ProfileProperties profile
    ) {
        List<NormalizedBlock> initialBlocks = parsedDocument.blocks().stream()
                .map(NormalizedBlock::fromParsedBlock)
                .toList();
        return PreprocessingContext.builder()
                .parsedDocument(parsedDocument)
                .workingBlocks(initialBlocks)
                .warnings(parsedDocument.warnings())
                .documentMetadata(parsedDocument.rawMetadata())
                .reviewProjection(ReviewProjection.builder().build())
                .profile(profile)
                .build();
    }

    public PreprocessingContext withWorkingBlocks(List<NormalizedBlock> nextBlocks) {
        return toBuilder().workingBlocks(nextBlocks).build();
    }

    public PreprocessingContext withWarnings(List<DocumentWarning> nextWarnings) {
        return toBuilder().warnings(nextWarnings).build();
    }

    public PreprocessingContext addStepMetric(String name, StepMetric metric) {
        Map<String, StepMetric> metrics = new LinkedHashMap<>(stepMetrics);
        metrics.put(name, metric);
        return toBuilder().stepMetrics(metrics).build();
    }

    public PreprocessingContext putDocumentMetadata(String key, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<>(documentMetadata);
        metadata.put(key, value);
        return toBuilder().documentMetadata(metadata).build();
    }

    public PreprocessingContext putQualitySignal(String key, Object value) {
        Map<String, Object> signals = new LinkedHashMap<>(qualitySignals);
        signals.put(key, value);
        return toBuilder().qualitySignals(signals).build();
    }

    public PreprocessingContext withReviewProjection(ReviewProjection nextReviewProjection) {
        return toBuilder().reviewProjection(nextReviewProjection).build();
    }

    public PreprocessingContext appendReviewBlocks(List<ReviewTextBlock> additionalBlocks) {
        if (additionalBlocks == null || additionalBlocks.isEmpty()) {
            return this;
        }
        return toBuilder()
                .reviewProjection(reviewProjection.append(additionalBlocks))
                .build();
    }
}
