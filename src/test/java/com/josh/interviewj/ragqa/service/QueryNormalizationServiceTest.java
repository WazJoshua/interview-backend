package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryNormalizationServiceTest {

    @Test
    void normalize_WhitespaceAndAliasExpansion_PreservesOriginalTerm() {
        QueryNormalizationService service = new QueryNormalizationService(properties(
                alias("aof", "append only file")
        ));

        NormalizedQuery normalized = service.normalize("  AOF   持久化　是什么？ ");

        assertThat(normalized.normalizedText()).isEqualTo("AOF 持久化 是什么? append only file");
        assertThat(normalized.aliasExpansions()).containsExactly("append only file");
    }

    @Test
    void normalize_AliasMatching_UsesNormalizedKeyAndLongestKeyFirst() {
        QueryNormalizationService service = new QueryNormalizationService(properties(
                alias("aof", "append only file"),
                alias("redis aof", "redis append only file")
        ));

        NormalizedQuery normalized = service.normalize(" Redis　AOF ");

        assertThat(normalized.normalizedText()).isEqualTo("Redis AOF redis append only file");
        assertThat(normalized.aliasExpansions()).containsExactly("redis append only file");
    }

    @Test
    void normalize_AliasTieBreak_UsesDeclarationOrderForSameLengthKeys() {
        QueryNormalizationService service = new QueryNormalizationService(properties(
                alias("svc", "service"),
                alias("svc", "storage virtual cluster")
        ));

        NormalizedQuery normalized = service.normalize("svc");

        assertThat(normalized.aliasExpansions()).containsExactly("service");
    }

    @Test
    void normalize_AliasExpansion_AppendsSameCanonicalOnlyOnce() {
        QueryNormalizationService service = new QueryNormalizationService(properties(
                alias("jwt", "json web token"),
                alias("JWT", "json web token")
        ));

        NormalizedQuery normalized = service.normalize("jwt 和 JWT");

        assertThat(normalized.aliasExpansions()).containsExactly("json web token");
        assertThat(normalized.normalizedText()).isEqualTo("jwt 和 JWT json web token");
    }

    @Test
    void normalize_StructuredTokens_AreCapturedAsPreservedTokens() {
        QueryNormalizationService service = new QueryNormalizationService(properties());

        NormalizedQuery normalized = service.normalize(
                "AUTH_006 spring.profiles.active /api/v1/knowledge-bases v1.09 Java 25 QueryEmbeddingService AOF"
        );

        assertThat(normalized.preservedTokens()).containsExactly(
                "AUTH_006",
                "spring.profiles.active",
                "/api/v1/knowledge-bases",
                "v1.09",
                "Java 25",
                "QueryEmbeddingService",
                "AOF"
        );
    }

    @Test
    void normalize_BacktickedAndCamelCaseTerms_AreAddedToProtectedTerms() {
        QueryNormalizationService service = new QueryNormalizationService(properties());

        NormalizedQuery normalized = service.normalize("帮我看 `spring.profiles.active` QueryEmbeddingService orderId123");

        assertThat(normalized.protectedTerms()).contains("spring.profiles.active", "QueryEmbeddingService", "orderId123");
    }

    @Test
    void normalize_BlankAfterTrim_ThrowsCurrentBlankInputException() {
        QueryNormalizationService service = new QueryNormalizationService(properties());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.normalize("   "));

        assertEquals("LLM_001", exception.getErrorCode());
        assertEquals("Embedding input is required", exception.getMessage());
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
}
