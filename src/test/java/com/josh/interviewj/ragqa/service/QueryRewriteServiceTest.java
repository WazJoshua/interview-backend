package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import com.josh.interviewj.ragqa.model.RewriteFallbackReason;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriteServiceTest {

    private final AiOperationGateway aiOperationGateway = mock(AiOperationGateway.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private QueryRewriteService queryRewriteService;

    @BeforeEach
    void setUp() {
        queryRewriteService = new QueryRewriteService(aiOperationGateway, objectMapper, properties(alias("jwt", "json web token")));
        lenient().when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                0L,
                "KNOWLEDGE_BASE_QUERY",
                "biz-1",
                "kb_query_rewrite",
                List.of("KB_QUERY_CREDITS"),
                Map.of()
        ));
    }

    @Test
    void rewrite_NotEligibleQuery_SkipsLlmCall() {
        RewriteResult result = queryRewriteService.rewrite(query(
                "AUTH_006",
                "AUTH_006",
                List.of("AUTH_006"),
                List.of("AUTH_006"),
                List.of(),
                new QueryProfile(true, false, true, false, false)
        ));

        assertThat(result.attempted()).isFalse();
        assertThat(result.finalText()).isEqualTo("AUTH_006");
        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.NOT_ELIGIBLE);
        verify(aiOperationGateway, never()).executeInvocation(any(), any(), any());
    }

    @Test
    void rewrite_EligibleQuery_ReturnsValidatedRewrite() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"JWT 登录 失败 json web token\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "jwt 登录失败怎么办",
                "jwt 登录失败怎么办 json web token",
                List.of("jwt"),
                List.of("jwt"),
                List.of("json web token"),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.attempted()).isTrue();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.finalText()).isEqualTo("JWT 登录 失败 json web token");

        ArgumentCaptor<AiInvocationInput> requestCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway).executeInvocation(any(), any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().userPrompt()).isEqualTo("jwt 登录失败怎么办 json web token");
    }

    @Test
    void rewrite_MissingPreservedToken_FallsBackWithReason() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"认证失败处理 json web token\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "spring.profiles.active 配置失败",
                "spring.profiles.active 配置失败",
                List.of("spring.profiles.active"),
                List.of("spring.profiles.active"),
                List.of(),
                new QueryProfile(false, false, true, true, false)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.PRESERVED_TOKEN_MISSING);
    }

    @Test
    void rewrite_DottedStructuredTokenSplit_DoesNotPassPreservedValidation() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"spring profiles active 配置失败\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "spring.profiles.active 配置失败",
                "spring.profiles.active 配置失败",
                List.of("spring.profiles.active"),
                List.of("spring.profiles.active"),
                List.of(),
                new QueryProfile(false, false, true, true, false)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.PRESERVED_TOKEN_MISSING);
    }

    @Test
    void rewrite_MissingProtectedTerm_FallsBackWithProtectedTermMissing() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"JWT 登录失败的排查步骤\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办 json web token",
                List.of("JWT"),
                List.of("JWT", "json web token"),
                List.of("json web token"),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.PROTECTED_TERM_MISSING);
    }

    @Test
    void rewrite_AnswerStyleContent_FallsBackWithReason() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"答案是 JWT 登录失败通常是 token 过期\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.ANSWER_STYLE_DETECTED);
    }

    @Test
    void rewrite_ExcessiveDrift_AddsUnjustifiedStrongTerm_FallsBack() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":\"JWT 登录失败 kubernetes service mesh 排障\"}",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.EXCESSIVE_DRIFT);
    }

    @Test
    void rewrite_TimeoutException_FallsBackWithTimeoutReason() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenThrow(
                new BusinessException("LLM_001", "LLM service call failed: TIMEOUT - upstream timeout",
                        new LlmException("timeout", "TIMEOUT", true))
        );

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.TIMEOUT);
    }

    @Test
    void rewrite_TopLevelEmptyResponseBusinessException_MapsToParseFailed() {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_001", "Empty response from LLM service"));

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.PARSE_FAILED);
    }

    @Test
    void rewriteWithExecution_MalformedJson_PreservesInvocationMetadataForFallbackAccounting() {
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                "{\"rewrittenQuery\":",
                "dispatcher_rc",
                "gpt-5.1-codex-mini",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        )));

        QueryRewriteService.RewriteExecutionResult result = queryRewriteService.rewriteWithExecution(
                query(
                        "JWT 登录失败怎么办",
                        "JWT 登录失败怎么办",
                        List.of("JWT"),
                        List.of("JWT"),
                        List.of(),
                        new QueryProfile(false, false, false, true, true)
                ),
                new BusinessOperationContext(
                        "biz-1",
                        1L,
                        "KNOWLEDGE_BASE_QUERY",
                        "kb-1",
                        "kb_query",
                        List.of("KB_QUERY_CREDITS"),
                        Map.of()
                ),
                "biz-1:rewrite"
        );

        assertThat(result.rewriteResult().fallbackReason()).isEqualTo(RewriteFallbackReason.PARSE_FAILED);
        assertThat(result.invocationContext()).isNotNull();
        assertThat(result.invocationResult()).isNotNull();
    }

    @Test
    void rewrite_WrappedBusinessException_RecursivelyUnwrapsCauseChain() {
        BusinessException exception = new BusinessException(
                "LLM_001",
                "LLM service call failed: RuntimeException - provider boom",
                new RuntimeException(new LlmException("timeout", "TIMEOUT", true))
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenThrow(exception);

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.TIMEOUT);
    }

    @Test
    void rewrite_ParseFailure_FallsBackWithParseFailed() {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenThrow(new BusinessException("LLM_001", "LLM output does not contain JSON"));

        RewriteResult result = queryRewriteService.rewrite(query(
                "JWT 登录失败怎么办",
                "JWT 登录失败怎么办",
                List.of("JWT"),
                List.of("JWT"),
                List.of(),
                new QueryProfile(false, false, false, true, true)
        ));

        assertThat(result.fallbackReason()).isEqualTo(RewriteFallbackReason.PARSE_FAILED);
    }

    private QueryUnderstandingProperties properties(QueryUnderstandingProperties.AliasEntry... aliases) {
        QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
        properties.setAliasDictionary(List.of(aliases));
        return properties;
    }

    private QueryUnderstandingProperties.AliasEntry alias(String term, String canonical) {
        QueryUnderstandingProperties.AliasEntry alias = new QueryUnderstandingProperties.AliasEntry();
        alias.setAlias(term);
        alias.setCanonical(canonical);
        return alias;
    }

    private NormalizedQuery query(
            String raw,
            String normalized,
            List<String> preservedTokens,
            List<String> protectedTerms,
            List<String> aliasExpansions,
            QueryProfile profile
    ) {
        return new NormalizedQuery(raw, normalized, preservedTokens, protectedTerms, aliasExpansions, profile);
    }
}
