package com.josh.interviewj.ragqa.model;

import java.util.List;

public record LiteralSignalProfile(
        boolean hasErrorCode,
        boolean hasConfigPath,
        boolean hasCliOption,
        boolean hasEnvVar,
        boolean hasCodeSymbol,
        boolean hasVersionToken,
        List<String> matchedSignalTypes,
        int matchedTokenCount,
        List<String> exactBoostTerms
) {

    public LiteralSignalProfile {
        matchedSignalTypes = matchedSignalTypes == null ? List.of() : List.copyOf(matchedSignalTypes);
        exactBoostTerms = exactBoostTerms == null ? List.of() : List.copyOf(exactBoostTerms);
    }

    public static LiteralSignalProfile none() {
        return new LiteralSignalProfile(false, false, false, false, false, false, List.of(), 0, List.of());
    }

    public static LiteralSignalProfile of(
            boolean hasErrorCode,
            boolean hasConfigPath,
            boolean hasCliOption,
            boolean hasEnvVar,
            boolean hasCodeSymbol,
            boolean hasVersionToken,
            List<String> matchedSignalTypes,
            int matchedTokenCount,
            List<String> exactBoostTerms
    ) {
        return new LiteralSignalProfile(
                hasErrorCode,
                hasConfigPath,
                hasCliOption,
                hasEnvVar,
                hasCodeSymbol,
                hasVersionToken,
                matchedSignalTypes,
                matchedTokenCount,
                exactBoostTerms
        );
    }
}
