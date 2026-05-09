package com.josh.interviewj.ragqa.model;

import java.util.List;

public record RetrievalPlan(
        Strategy strategy,
        List<RetrievalBranch> branches,
        int candidateTopKPerBranch,
        int finalContextTopK,
        BranchExecutionMode branchExecutionMode
) {
    public RetrievalPlan {
        branches = List.copyOf(branches);
    }

    public enum Strategy {
        SINGLE_ORIGINAL,
        DUAL_ORIGINAL_REWRITE,
        HYBRID_ORIGINAL_SPARSE,
        HYBRID_ORIGINAL_REWRITE
    }

    public enum BranchExecutionMode {
        SERIAL,
        PARALLEL
    }
}
