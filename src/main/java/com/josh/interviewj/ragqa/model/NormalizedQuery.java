package com.josh.interviewj.ragqa.model;

import java.util.List;

public record NormalizedQuery(
        String rawText,
        String normalizedText,
        List<String> preservedTokens,
        List<String> protectedTerms,
        List<String> aliasExpansions,
        QueryProfile profile,
        LiteralSignalProfile literalSignals
) {
    public NormalizedQuery {
        preservedTokens = List.copyOf(preservedTokens);
        protectedTerms = List.copyOf(protectedTerms);
        aliasExpansions = List.copyOf(aliasExpansions);
        literalSignals = literalSignals == null ? LiteralSignalProfile.none() : literalSignals;
    }

    public NormalizedQuery(
            String rawText,
            String normalizedText,
            List<String> preservedTokens,
            List<String> protectedTerms,
            List<String> aliasExpansions,
            QueryProfile profile
    ) {
        this(rawText, normalizedText, preservedTokens, protectedTerms, aliasExpansions, profile, LiteralSignalProfile.none());
    }

    public static NormalizedQuery raw(String text) {
        return new NormalizedQuery(text, text, List.of(), List.of(), List.of(), QueryProfile.none(), LiteralSignalProfile.none());
    }
}
