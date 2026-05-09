package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.RewriteFallbackReason;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QueryRewriteService {

    private static final String PURPOSE_QUERY_REWRITE = "kb_query_rewrite";
    private static final String SYSTEM_PROMPT = """
            Rewrite the query for retrieval only.
            Preserve structured tokens and protected terminology.
            Return JSON only with {"rewrittenQuery":"..."}.
            """;
    private static final int MAX_REWRITE_CHARS = 240;
    private static final Set<String> ANSWER_STYLE_PREFIXES = Set.of("是", "指的是", "通常是", "意味着", "答案是", "总结来说");
    private static final Pattern ENUMERATED_ANSWER_PATTERN = Pattern.compile("^(?:\\d+[\\.)]|[一二三四五六七八九十]、).*");
    private static final Set<String> STOP_WORDS = Set.of(
            "怎么", "如何", "有没有", "能不能", "更稳", "比较保险", "帮我看", "请问", "什么", "怎么办", "一下", "一个", "这个", "那个"
    );

    private final AiOperationGateway aiOperationGateway;
    private final ObjectMapper objectMapper;
    private final QueryUnderstandingProperties properties;

    public QueryRewriteService(AiOperationGateway aiOperationGateway, ObjectMapper objectMapper, QueryUnderstandingProperties properties) {
        this.aiOperationGateway = aiOperationGateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public RewriteResult rewrite(NormalizedQuery normalizedQuery) {
        String businessOperationId = PURPOSE_QUERY_REWRITE + "-" + UUID.randomUUID();
        return rewriteWithExecution(
                normalizedQuery,
                new BusinessOperationContext(
                        businessOperationId,
                        0L,
                        "KNOWLEDGE_BASE_QUERY",
                        businessOperationId,
                        PURPOSE_QUERY_REWRITE,
                        List.of("KB_QUERY_CREDITS"),
                        Map.of()
                ),
                businessOperationId + ":rewrite"
        ).rewriteResult();
    }

    public RewriteExecutionResult rewriteWithExecution(
            NormalizedQuery normalizedQuery,
            BusinessOperationContext operationContext,
            String invocationId
    ) {
        if (!isEligible(normalizedQuery)) {
            return new RewriteExecutionResult(RewriteResult.notAttempted(normalizedQuery.normalizedText()), null, null);
        }

        AiInvocationContext invocationContext = new AiInvocationContext(
                invocationId,
                PURPOSE_QUERY_REWRITE,
                UsageFamily.CHAT,
                "KB_QUERY_CREDITS",
                true,
                Map.of()
        );
            AiInvocationResult invocationResult = null;

        try {
            invocationResult = aiOperationGateway.executeInvocation(
                    operationContext,
                    invocationContext,
                    AiInvocationInput.chat(SYSTEM_PROMPT, normalizedQuery.normalizedText(), null,
                            new PromptTemplateRef("kb_query_rewrite", Map.of()))
            );
            LlmResponse response = invocationResult.llmResponse();

            String rewrittenQuery;
            try {
                rewrittenQuery = extractRewriteCandidate(response);
            } catch (Exception exception) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.PARSE_FAILED),
                        invocationContext,
                        invocationResult
                );
            }
            String normalizedCandidate = normalizeCandidate(rewrittenQuery);
            if (normalizedCandidate.isBlank()) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.EMPTY_RESULT, response),
                        invocationContext,
                        invocationResult
                );
            }
            if (normalizedCandidate.length() > MAX_REWRITE_CHARS) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.LENGTH_EXCEEDED, response),
                        invocationContext,
                        invocationResult
                );
            }
            if (isMissingPreservedToken(normalizedQuery, normalizedCandidate)) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.PRESERVED_TOKEN_MISSING, response),
                        invocationContext,
                        invocationResult
                );
            }
            if (isMissingProtectedTerm(normalizedQuery, normalizedCandidate)) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.PROTECTED_TERM_MISSING, response),
                        invocationContext,
                        invocationResult
                );
            }
            if (looksLikeAnswer(normalizedCandidate)) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.ANSWER_STYLE_DETECTED, response),
                        invocationContext,
                        invocationResult
                );
            }
            if (hasExcessiveDrift(normalizedQuery, normalizedCandidate)) {
                return new RewriteExecutionResult(
                        RewriteResult.failed(normalizedQuery.normalizedText(), RewriteFallbackReason.EXCESSIVE_DRIFT, response),
                        invocationContext,
                        invocationResult
                );
            }
            return new RewriteExecutionResult(RewriteResult.succeeded(normalizedCandidate, response), invocationContext, invocationResult);
        } catch (Exception exception) {
            return new RewriteExecutionResult(
                    RewriteResult.failed(normalizedQuery.normalizedText(), classifyFallbackReason(exception)),
                    invocationContext,
                    invocationResult
            );
        }
    }

    private boolean isEligible(NormalizedQuery normalizedQuery) {
        return normalizedQuery.profile().likelyConversational()
                || normalizedQuery.profile().longQuery()
                || normalizedQuery.profile().likelyTerminologyDrift()
                || (normalizedQuery.profile().shortQuery() && !normalizedQuery.profile().hasStructuredTokens());
    }

    private String extractRewriteCandidate(LlmResponse response) throws Exception {
        JsonNode root = objectMapper.readTree(response.content());
        JsonNode rewrittenQuery = root.get("rewrittenQuery");
        return rewrittenQuery == null || rewrittenQuery.isNull() ? "" : rewrittenQuery.asText("");
    }

    private String normalizeCandidate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.isBlank()) {
            return "";
        }
        return QueryNormalizationService.normalizeSurface(trimmed);
    }

    private boolean isMissingPreservedToken(NormalizedQuery normalizedQuery, String candidate) {
        String candidateComparison = QueryNormalizationService.normalizeForComparison(candidate);
        return normalizedQuery.preservedTokens().stream()
                .map(QueryNormalizationService::normalizeForComparison)
                .anyMatch(token -> !candidateComparison.contains(token));
    }

    private boolean isMissingProtectedTerm(NormalizedQuery normalizedQuery, String candidate) {
        String candidateComparison = QueryNormalizationService.normalizeForComparison(candidate);
        return normalizedQuery.protectedTerms().stream()
                .map(QueryNormalizationService::normalizeForComparison)
                .anyMatch(term -> !candidateComparison.contains(term));
    }

    private boolean looksLikeAnswer(String candidate) {
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        if (ANSWER_STYLE_PREFIXES.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::startsWith)) {
            return true;
        }
        if (normalized.contains("答案是") || normalized.contains("总结来说")) {
            return true;
        }
        return ENUMERATED_ANSWER_PATTERN.matcher(candidate.strip()).matches();
    }

    private boolean hasExcessiveDrift(NormalizedQuery normalizedQuery, String candidate) {
        List<String> candidateTerms = extractTerms(candidate);
        List<String> originalTerms = extractTerms(normalizedQuery.normalizedText());
        Set<String> allowedTerms = new HashSet<>(originalTerms);
        normalizedQuery.aliasExpansions().forEach(expansion -> allowedTerms.addAll(extractTerms(expansion)));

        boolean keepsTopic = candidateTerms.stream().anyMatch(term -> originalTerms.stream().anyMatch(original -> original.contains(term) || term.contains(original)));
        if (!normalizedQuery.protectedTerms().isEmpty() && !keepsTopic) {
            return true;
        }

        return candidateTerms.stream()
                .filter(this::isStrongTerm)
                .anyMatch(term -> !allowedTerms.contains(term));
    }

    private List<String> extractTerms(String text) {
        String normalized = QueryNormalizationService.normalizeForComparison(text)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5 ]", " ");
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean isStrongTerm(String term) {
        return term.matches("[a-z][a-z0-9_-]{3,}");
    }

    private RewriteFallbackReason classifyFallbackReason(Throwable throwable) {
        if (containsLlmTimeout(throwable)) {
            return RewriteFallbackReason.TIMEOUT;
        }
        if (containsParseFailure(throwable)) {
            return RewriteFallbackReason.PARSE_FAILED;
        }
        return RewriteFallbackReason.LLM_ERROR;
    }

    private boolean containsLlmTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof LlmException llmException && "TIMEOUT".equalsIgnoreCase(llmException.getReason())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsParseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BusinessException businessException
                    && "LLM_001".equals(businessException.getErrorCode())
                    && isParseFailureMessage(businessException.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isParseFailureMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("Empty response from LLM service")
                || message.contains("LLM returned empty content")
                || message.contains("LLM output does not contain JSON");
    }

    public record RewriteExecutionResult(
            RewriteResult rewriteResult,
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }
}
