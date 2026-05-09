package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalDecisionType;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

@Component
public class FixedSizeChunkingInputAdapter {

    public FixedSizeChunkingInput adapt(NormalizedDocument normalizedDocument) {
        StringBuilder builder = new StringBuilder();
        List<FixedSizeChunkingInput.RetainedSegment> segments = new ArrayList<>();
        List<String> documentWarnings = normalizedDocument.warnings().stream()
                .map(warning -> warning.code())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        boolean hasStructuralWarning = normalizedDocument.warnings().stream()
                .anyMatch(warning -> warning.category() == DocumentWarningCategory.STRUCTURAL);

        for (NormalizedBlock block : normalizedDocument.blocks()) {
            RetrievalDisposition retrievalDisposition = resolveRetrievalDisposition(block);
            if (retrievalDisposition == RetrievalDisposition.SOFT_DEINDEX
                    || retrievalDisposition == RetrievalDisposition.DROP) {
                continue;
            }
            String text = block.text() == null ? "" : block.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            int startOffset = builder.length();
            if (builder.length() > 0) {
                builder.append("\n\n");
                startOffset = builder.length();
            }
            builder.append(text);
            int endOffset = builder.length();

            List<RetrievalDispositionReasonCode> retrievalDispositionReasonCodes = resolveRetrievalDispositionReasonCodes(block);
            List<String> retrievalDispositionEvidence = stringList(block.metadata().get("retrievalDispositionEvidence"));
            Set<String> qualityFlags = new LinkedHashSet<>();
            if (retrievalDisposition == RetrievalDisposition.PROTECT) {
                qualityFlags.add("HAS_PROTECTED_ANCHOR");
            }
            if (hasStructuralWarning) {
                qualityFlags.add("HAS_STRUCTURAL_WARNING");
            }

            segments.add(FixedSizeChunkingInput.RetainedSegment.builder()
                    .blockOrder(block.order())
                    .startOffset(startOffset)
                    .endOffset(endOffset)
                    .pageNumber(block.pageNumber())
                    .blockType(block.type())
                    .retrievalDisposition(retrievalDisposition)
                    .retrievalDispositionReasonCodes(retrievalDispositionReasonCodes)
                    .retrievalDispositionEvidence(retrievalDispositionEvidence)
                    .qualityFlags(qualityFlags.stream().sorted().toList())
                    .preprocessingWarnings(documentWarnings)
                    .build());
        }

        return FixedSizeChunkingInput.builder()
                .normalizedTextForChunking(builder.toString())
                .retainedSegments(segments)
                .build();
    }

    private RetrievalDisposition resolveRetrievalDisposition(NormalizedBlock block) {
        Object canonicalDisposition = block.metadata().get("retrievalDisposition");
        if (canonicalDisposition instanceof String canonicalDispositionValue && !canonicalDispositionValue.isBlank()) {
            return parseRetrievalDisposition(canonicalDispositionValue)
                    .orElse(RetrievalDisposition.KEEP);
        }
        Object legacyDecision = block.metadata().get("dropLowSignalDecision");
        if (legacyDecision instanceof String legacyDecisionValue && !legacyDecisionValue.isBlank()) {
            return parseLegacyDecisionType(legacyDecisionValue)
                    .map(decisionType -> switch (decisionType) {
                        case PROTECT -> RetrievalDisposition.PROTECT;
                        case KEEP -> RetrievalDisposition.KEEP;
                        case WARN -> RetrievalDisposition.SOFT_DEINDEX;
                        case DROP -> RetrievalDisposition.DROP;
                    })
                    .orElse(RetrievalDisposition.KEEP);
        }
        return RetrievalDisposition.KEEP;
    }

    private List<RetrievalDispositionReasonCode> resolveRetrievalDispositionReasonCodes(NormalizedBlock block) {
        List<String> canonicalReasonCodes = stringList(block.metadata().get("retrievalDispositionReasonCodes"));
        if (!canonicalReasonCodes.isEmpty()) {
            return parseCanonicalReasonCodes(canonicalReasonCodes);
        }
        List<String> legacyReasonCodes = stringList(block.metadata().get("dropLowSignalReasonCodes"));
        if (!legacyReasonCodes.isEmpty()) {
            return legacyReasonCodes.stream()
                    .map(value -> RetrievalDispositionReasonCode.valueOf(mapLegacyReasonCode(value)))
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    private Optional<RetrievalDisposition> parseRetrievalDisposition(String value) {
        try {
            return Optional.of(RetrievalDisposition.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<LowSignalDecisionType> parseLegacyDecisionType(String value) {
        try {
            return Optional.of(LowSignalDecisionType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private List<RetrievalDispositionReasonCode> parseCanonicalReasonCodes(List<String> rawReasonCodes) {
        List<RetrievalDispositionReasonCode> parsedReasonCodes = new ArrayList<>();
        boolean hasUnknownReasonCode = false;
        for (String rawReasonCode : rawReasonCodes) {
            try {
                parsedReasonCodes.add(RetrievalDispositionReasonCode.valueOf(rawReasonCode.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                hasUnknownReasonCode = true;
            }
        }
        if (hasUnknownReasonCode && !parsedReasonCodes.contains(RetrievalDispositionReasonCode.COMPATIBILITY_FALLBACK)) {
            parsedReasonCodes.add(RetrievalDispositionReasonCode.COMPATIBILITY_FALLBACK);
        }
        return List.copyOf(parsedReasonCodes);
    }

    private String mapLegacyReasonCode(String legacyReasonCode) {
        return switch (legacyReasonCode) {
            case "PROTECTED_BLOCK_TYPE",
                    "PROTECTED_ERROR_CODE",
                    "PROTECTED_PATH_OR_URL",
                    "PROTECTED_COMMAND",
                    "PROTECTED_TABLE_LABEL" -> "PROTECTED_TECHNICAL_ANCHOR";
            case "WARN_SHORT_FRAGMENT" -> "SHORT_FRAGMENT";
            case "WARN_HIGH_SYMBOL_RATIO" -> "HIGH_SYMBOL_RATIO";
            case "WARN_POSSIBLE_APPENDIX_SAMPLE_DATA" -> "APPENDIX_SAMPLE_PAYLOAD";
            case "DROP_SEPARATOR_PATTERN" -> "SEPARATOR_PATTERN";
            case "DROP_PAGE_NUMBER" -> "PAGE_NUMBER_ARTIFACT";
            case "DROP_TOC_FRAGMENT", "DROP_REPEATED_FOOTER" -> "TOC_NAVIGATION_ARTIFACT";
            case "DROP_LOW_READABILITY" -> "LOW_READABILITY_GARBAGE";
            default -> "COMPATIBILITY_FALLBACK";
        };
    }
}
