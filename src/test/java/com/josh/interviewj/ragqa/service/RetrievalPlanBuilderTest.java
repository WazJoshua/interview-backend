package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.config.HybridRetrievalProperties;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.ragqa.model.RetrievalPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPlanBuilderTest {

    private final QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
    private final HybridRetrievalProperties hybridRetrievalProperties = new HybridRetrievalProperties();
    private final RetrievalPlanBuilder retrievalPlanBuilder = new RetrievalPlanBuilder(properties, hybridRetrievalProperties);

    @Test
    void build_WhenRewriteDisabled_UsesSingleOriginalStrategy() {
        NormalizedQuery normalizedQuery = new NormalizedQuery(
                "AOF 持久化",
                "AOF 持久化 append only file",
                List.of("AOF"),
                List.of("AOF"),
                List.of("append only file"),
                new QueryProfile(true, false, true, false, true)
        );

        RetrievalPlan plan = retrievalPlanBuilder.build(normalizedQuery, 5);

        assertThat(plan.strategy()).isEqualTo(RetrievalPlan.Strategy.SINGLE_ORIGINAL);
        assertThat(plan.branches()).hasSize(1);
        assertThat(plan.branches().getFirst().queryVariant()).isEqualTo(com.josh.interviewj.ragqa.model.QueryVariant.ORIGINAL);
        assertThat(plan.branches().getFirst().retrievalMode()).isEqualTo(com.josh.interviewj.ragqa.model.RetrievalMode.DENSE);
        assertThat(plan.branches().getFirst().queryText()).isEqualTo("AOF 持久化 append only file");
        assertThat(plan.candidateTopKPerBranch()).isEqualTo(5);
        assertThat(plan.finalContextTopK()).isEqualTo(5);
        assertThat(plan.branchExecutionMode()).isEqualTo(RetrievalPlan.BranchExecutionMode.SERIAL);
    }

    @Test
    void build_WhenRewriteSucceeded_UsesDualOriginalRewriteStrategy() {
        properties.setDualBranchEnabled(true);
        NormalizedQuery normalizedQuery = new NormalizedQuery(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办 json web token",
                List.of("JWT"),
                List.of("JWT", "json web token"),
                List.of("json web token"),
                new QueryProfile(false, false, false, true, true)
        );

        RetrievalPlan plan = retrievalPlanBuilder.build(normalizedQuery, RewriteResult.succeeded("JWT 登录失败 json web token"), 5);

        assertThat(plan.strategy()).isEqualTo(RetrievalPlan.Strategy.DUAL_ORIGINAL_REWRITE);
        assertThat(plan.branches()).hasSize(2);
        assertThat(plan.branches().get(0).queryVariant()).isEqualTo(com.josh.interviewj.ragqa.model.QueryVariant.ORIGINAL);
        assertThat(plan.branches().get(1).queryVariant()).isEqualTo(com.josh.interviewj.ragqa.model.QueryVariant.REWRITE);
        assertThat(plan.branches().get(0).queryText()).isEqualTo("JWT 登录失败怎么办 json web token");
        assertThat(plan.branches().get(1).queryText()).isEqualTo("JWT 登录失败 json web token");
        assertThat(plan.candidateTopKPerBranch()).isEqualTo(8);
        assertThat(plan.branchExecutionMode()).isEqualTo(RetrievalPlan.BranchExecutionMode.PARALLEL);
    }

    @Test
    void build_HybridEligibleQuery_AddsOriginalSparseBranch() throws Exception {
        Class<?> literalSignalProfileClass = Class.forName("com.josh.interviewj.ragqa.model.LiteralSignalProfile");
        Object literalSignalProfile = literalSignalProfileClass.getMethod(
                "of",
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                List.class,
                int.class,
                List.class
        ).invoke(
                null,
                true,
                true,
                false,
                true,
                false,
                false,
                List.of("ERROR_CODE", "CONFIG_PATH", "CLI_OPTION"),
                3,
                List.of("AUTH_001", "spring.profiles.active", "--tests")
        );

        Object normalizedQuery = NormalizedQuery.class.getConstructor(
                        String.class,
                        String.class,
                        List.class,
                        List.class,
                        List.class,
                        QueryProfile.class,
                        literalSignalProfileClass
                )
                .newInstance(
                        "AUTH_001 spring.profiles.active --tests",
                        "AUTH_001 spring.profiles.active --tests",
                        List.of("AUTH_001", "spring.profiles.active", "--tests"),
                        List.of("AUTH_001", "spring.profiles.active", "--tests"),
                        List.of(),
                        new QueryProfile(false, false, true, false, false),
                        literalSignalProfile
                );

        Class<?> eligibilityClass = Class.forName("com.josh.interviewj.ragqa.model.HybridRetrievalEligibility");
        Object eligibility = eligibilityClass.getMethod("enabled", boolean.class, boolean.class, boolean.class)
                .invoke(null, true, true, true);

        Object plan = RetrievalPlanBuilder.class
                .getMethod("build", NormalizedQuery.class, RewriteResult.class, eligibilityClass, int.class)
                .invoke(
                        retrievalPlanBuilder,
                        normalizedQuery,
                        RewriteResult.notAttempted("AUTH_001 spring.profiles.active --tests"),
                        eligibility,
                        5
                );

        @SuppressWarnings("unchecked")
        List<Object> branches = (List<Object>) plan.getClass().getMethod("branches").invoke(plan);

        assertThat(branches).hasSize(2);
        assertThat(stringValue(branches.get(0), "queryVariant")).isEqualTo("ORIGINAL");
        assertThat(stringValue(branches.get(0), "retrievalMode")).isEqualTo("DENSE");
        assertThat(stringValue(branches.get(1), "queryVariant")).isEqualTo("ORIGINAL");
        assertThat(stringValue(branches.get(1), "retrievalMode")).isEqualTo("SPARSE");
    }

    @Test
    void build_HybridEligibleQuery_UsesConfiguredCandidateBudgets() throws Exception {
        hybridRetrievalProperties.setDenseCandidateTopKPerBranch(11);
        hybridRetrievalProperties.setSparseCandidateTopK(17);

        Class<?> literalSignalProfileClass = Class.forName("com.josh.interviewj.ragqa.model.LiteralSignalProfile");
        Object literalSignalProfile = literalSignalProfileClass.getMethod(
                "of",
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                List.class,
                int.class,
                List.class
        ).invoke(
                null,
                true,
                true,
                false,
                false,
                false,
                false,
                List.of("ERROR_CODE", "CONFIG_PATH"),
                2,
                List.of("AUTH_001", "spring.profiles.active")
        );
        Object normalizedQuery = NormalizedQuery.class.getConstructor(
                        String.class,
                        String.class,
                        List.class,
                        List.class,
                        List.class,
                        QueryProfile.class,
                        literalSignalProfileClass
                )
                .newInstance(
                        "AUTH_001 spring.profiles.active",
                        "AUTH_001 spring.profiles.active",
                        List.of("AUTH_001", "spring.profiles.active"),
                        List.of("AUTH_001", "spring.profiles.active"),
                        List.of(),
                        new QueryProfile(false, false, true, false, false),
                        literalSignalProfile
                );

        Class<?> eligibilityClass = Class.forName("com.josh.interviewj.ragqa.model.HybridRetrievalEligibility");
        Object eligibility = eligibilityClass.getMethod("enabled", boolean.class, boolean.class, boolean.class)
                .invoke(null, true, true, true);

        Object plan = RetrievalPlanBuilder.class
                .getMethod("build", NormalizedQuery.class, RewriteResult.class, eligibilityClass, int.class)
                .invoke(
                        retrievalPlanBuilder,
                        normalizedQuery,
                        RewriteResult.notAttempted("AUTH_001 spring.profiles.active"),
                        eligibility,
                        5
                );

        @SuppressWarnings("unchecked")
        List<Object> branches = (List<Object>) plan.getClass().getMethod("branches").invoke(plan);

        assertThat(intValue(branches.get(0), "candidateTopK")).isEqualTo(11);
        assertThat(intValue(branches.get(1), "candidateTopK")).isEqualTo(17);
    }

    private String stringValue(Object target, String accessor) throws Exception {
        return String.valueOf(target.getClass().getMethod(accessor).invoke(target));
    }

    private int intValue(Object target, String accessor) throws Exception {
        return (int) target.getClass().getMethod(accessor).invoke(target);
    }
}
