package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import lombok.Builder;

@Builder
public record LowSignalBlockFeatures(
        int textLength,
        double symbolRatio,
        double nonReadableRatio,
        double naturalLanguageRatio,
        double hexLikeRatio,
        int literalLineRun,
        int maxLiteralLineLength,
        boolean hasSectionKeyword,
        boolean payloadSplitBlock,
        boolean hasErrorCode,
        boolean hasPathOrUrl,
        boolean hasCommand
    ) {
}
