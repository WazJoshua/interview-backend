package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class QueryProfileDetector {

    private static final List<String> CONVERSATIONAL_CUES = List.of("怎么", "如何", "有没有", "能不能", "更稳", "比较保险", "帮我看", "请问");
    private static final Set<String> CONNECTOR_TOKENS = Set.of("怎么", "如何", "有没有", "能不能", "更稳", "比较保险", "帮我看", "请问", "怎么配", "和", "与", "及", "的", "了");

    public QueryProfile detect(NormalizedQuery normalizedQuery) {
        String normalizedText = normalizedQuery.normalizedText();
        int tokenCount = normalizedText.isBlank() ? 0 : normalizedText.split(" ").length;
        boolean shortQuery = normalizedText.length() <= 24 || tokenCount <= 4;
        boolean longQuery = normalizedText.length() >= 120 || tokenCount >= 20;
        boolean hasStructuredTokens = !normalizedQuery.preservedTokens().isEmpty();
        boolean likelyConversational = containsConversationalCue(normalizedText)
                && !isStructuredDominant(normalizedText, normalizedQuery.preservedTokens());
        boolean likelyTerminologyDrift = !normalizedQuery.aliasExpansions().isEmpty()
                || normalizedQuery.preservedTokens().stream().anyMatch(this::isAcronymToken);

        return new QueryProfile(shortQuery, longQuery, hasStructuredTokens, likelyConversational, likelyTerminologyDrift);
    }

    private boolean containsConversationalCue(String text) {
        return CONVERSATIONAL_CUES.stream().anyMatch(text::contains);
    }

    private boolean isStructuredDominant(String text, List<String> preservedTokens) {
        if (preservedTokens.isEmpty()) {
            return false;
        }

        String residual = text;
        for (String preservedToken : preservedTokens) {
            residual = residual.replace(preservedToken, " ");
        }

        long remainingTerms = List.of(residual.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5 ]", " ")
                        .trim()
                        .split("\\s+"))
                .stream()
                .filter(token -> !token.isBlank())
                .filter(token -> !CONNECTOR_TOKENS.contains(token.toLowerCase(Locale.ROOT)))
                .count();
        return remainingTerms <= 1;
    }

    private boolean isAcronymToken(String token) {
        return token.matches("[A-Z]{2,10}");
    }
}
