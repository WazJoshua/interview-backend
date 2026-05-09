package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.HybridRetrievalProperties;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.HybridRetrievalEligibility;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.ragqa.model.RetrievalBranch;
import com.josh.interviewj.ragqa.model.RetrievalPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RetrievalPlanBuilder {

    private final QueryUnderstandingProperties properties;
    private final HybridRetrievalProperties hybridRetrievalProperties;

    @Autowired
    public RetrievalPlanBuilder(QueryUnderstandingProperties properties, HybridRetrievalProperties hybridRetrievalProperties) {
        this.properties = properties;
        this.hybridRetrievalProperties = hybridRetrievalProperties;
    }

    public RetrievalPlanBuilder(QueryUnderstandingProperties properties) {
        this(properties, new HybridRetrievalProperties());
    }

    public RetrievalPlan build(
            NormalizedQuery normalizedQuery,
            RewriteResult rewriteResult,
            HybridRetrievalEligibility eligibility,
            int finalContextTopK
    ) {
        boolean useConfiguredDenseBudget = (rewriteResult.succeeded() && properties.isDualBranchEnabled())
                || eligibility.sparseBranchEnabled();
        int denseCandidateTopK = useConfiguredDenseBudget
                ? Math.max(finalContextTopK, hybridRetrievalProperties.getDenseCandidateTopKPerBranch())
                : finalContextTopK;
        int sparseCandidateTopK = Math.max(finalContextTopK, hybridRetrievalProperties.getSparseCandidateTopK());

        List<RetrievalBranch> branches = new ArrayList<>();
        RetrievalPlan.Strategy strategy = RetrievalPlan.Strategy.SINGLE_ORIGINAL;
        RetrievalPlan.BranchExecutionMode branchExecutionMode = RetrievalPlan.BranchExecutionMode.SERIAL;

        if (rewriteResult.succeeded() && properties.isDualBranchEnabled()) {
            branches.add(RetrievalBranch.dense(QueryVariant.ORIGINAL, normalizedQuery.normalizedText(), denseCandidateTopK));
            branches.add(RetrievalBranch.dense(QueryVariant.REWRITE, rewriteResult.finalText(), denseCandidateTopK));
            strategy = RetrievalPlan.Strategy.DUAL_ORIGINAL_REWRITE;
            branchExecutionMode = RetrievalPlan.BranchExecutionMode.PARALLEL;
        } else if (rewriteResult.succeeded()) {
            branches.add(RetrievalBranch.dense(QueryVariant.ORIGINAL, rewriteResult.finalText(), denseCandidateTopK));
        } else {
            branches.add(RetrievalBranch.dense(QueryVariant.ORIGINAL, normalizedQuery.normalizedText(), denseCandidateTopK));
        }

        if (eligibility.sparseBranchEnabled()) {
            branches.add(RetrievalBranch.sparse(
                    QueryVariant.ORIGINAL,
                    normalizedQuery.normalizedText(),
                    sparseCandidateTopK,
                    normalizedQuery.literalSignals().exactBoostTerms()
            ));
            strategy = rewriteResult.succeeded() && properties.isDualBranchEnabled()
                    ? RetrievalPlan.Strategy.HYBRID_ORIGINAL_REWRITE
                    : RetrievalPlan.Strategy.HYBRID_ORIGINAL_SPARSE;
            branchExecutionMode = RetrievalPlan.BranchExecutionMode.PARALLEL;
        }

        return new RetrievalPlan(strategy, branches, denseCandidateTopK, finalContextTopK, branchExecutionMode);
    }

    public RetrievalPlan build(NormalizedQuery normalizedQuery, int finalContextTopK) {
        return build(
                normalizedQuery,
                RewriteResult.notAttempted(normalizedQuery.normalizedText()),
                HybridRetrievalEligibility.disabled(),
                finalContextTopK
        );
    }

    public RetrievalPlan build(NormalizedQuery normalizedQuery, RewriteResult rewriteResult, int finalContextTopK) {
        return build(normalizedQuery, rewriteResult, HybridRetrievalEligibility.disabled(), finalContextTopK);
    }
}
