package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import java.util.ArrayList;
import java.util.List;

public enum RetrievalDispositionReasonCode {
    PROTECTED_TECHNICAL_ANCHOR,
    SHORT_FRAGMENT,
    HIGH_SYMBOL_RATIO,
    APPENDIX_SAMPLE_PAYLOAD,
    SEPARATOR_PATTERN,
    PAGE_NUMBER_ARTIFACT,
    TOC_NAVIGATION_ARTIFACT,
    LOW_READABILITY_GARBAGE,
    COMPATIBILITY_FALLBACK;

    public static List<RetrievalDispositionReasonCode> fromLegacyReasonCodes(List<LowSignalReasonCode> legacyReasonCodes) {
        if (legacyReasonCodes == null || legacyReasonCodes.isEmpty()) {
            return List.of();
        }
        List<RetrievalDispositionReasonCode> mapped = new ArrayList<>();
        for (LowSignalReasonCode legacyReasonCode : legacyReasonCodes) {
            RetrievalDispositionReasonCode canonicalReasonCode = switch (legacyReasonCode) {
                case PROTECTED_BLOCK_TYPE,
                        PROTECTED_ERROR_CODE,
                        PROTECTED_PATH_OR_URL,
                        PROTECTED_COMMAND,
                        PROTECTED_TABLE_LABEL -> PROTECTED_TECHNICAL_ANCHOR;
                case WARN_SHORT_FRAGMENT -> SHORT_FRAGMENT;
                case WARN_HIGH_SYMBOL_RATIO -> HIGH_SYMBOL_RATIO;
                case WARN_POSSIBLE_APPENDIX_SAMPLE_DATA -> APPENDIX_SAMPLE_PAYLOAD;
                case DROP_SEPARATOR_PATTERN -> SEPARATOR_PATTERN;
                case DROP_PAGE_NUMBER -> PAGE_NUMBER_ARTIFACT;
                case DROP_TOC_FRAGMENT,
                        DROP_REPEATED_FOOTER -> TOC_NAVIGATION_ARTIFACT;
                case DROP_LOW_READABILITY -> LOW_READABILITY_GARBAGE;
            };
            if (!mapped.contains(canonicalReasonCode)) {
                mapped.add(canonicalReasonCode);
            }
        }
        return List.copyOf(mapped);
    }
}
