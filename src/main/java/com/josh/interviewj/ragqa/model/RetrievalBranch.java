package com.josh.interviewj.ragqa.model;

import java.util.List;

public record RetrievalBranch(
        QueryVariant queryVariant,
        RetrievalMode retrievalMode,
        String queryText,
        int candidateTopK,
        List<String> exactBoostTerms
) {
    public static RetrievalBranch dense(QueryVariant queryVariant, String queryText, int candidateTopK) {
        return new RetrievalBranch(queryVariant, RetrievalMode.DENSE, queryText, candidateTopK, List.of());
    }

    public static RetrievalBranch sparse(
            QueryVariant queryVariant,
            String queryText,
            int candidateTopK,
            List<String> exactBoostTerms
    ) {
        return new RetrievalBranch(queryVariant, RetrievalMode.SPARSE, queryText, candidateTopK, exactBoostTerms);
    }

    public RetrievalBranch {
        exactBoostTerms = exactBoostTerms == null ? List.of() : List.copyOf(exactBoostTerms);
    }
}
