package com.josh.interviewj.ragqa.model;

public record QueryProfile(
        boolean shortQuery,
        boolean longQuery,
        boolean hasStructuredTokens,
        boolean likelyConversational,
        boolean likelyTerminologyDrift
) {
    public static QueryProfile none() {
        return new QueryProfile(false, false, false, false, false);
    }
}
