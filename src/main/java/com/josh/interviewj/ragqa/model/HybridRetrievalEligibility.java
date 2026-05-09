package com.josh.interviewj.ragqa.model;

public record HybridRetrievalEligibility(
        boolean sparseGloballyEnabled,
        boolean kbSparseReady,
        boolean sparseAllowedForQuery
) {

    public static HybridRetrievalEligibility disabled() {
        return new HybridRetrievalEligibility(false, false, false);
    }

    public static HybridRetrievalEligibility enabled(
            boolean sparseGloballyEnabled,
            boolean kbSparseReady,
            boolean sparseAllowedForQuery
    ) {
        return new HybridRetrievalEligibility(sparseGloballyEnabled, kbSparseReady, sparseAllowedForQuery);
    }

    public boolean sparseBranchEnabled() {
        return sparseGloballyEnabled && kbSparseReady && sparseAllowedForQuery;
    }
}
