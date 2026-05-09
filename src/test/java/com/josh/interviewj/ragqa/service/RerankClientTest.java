package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RerankClientTest {

    private DatabaseRerankConfigResolver databaseRerankConfigResolver;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private RerankClient rerankClient;

    @BeforeEach
    void setUp() {
        databaseRerankConfigResolver = new DatabaseRerankConfigResolver(() -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("kb_query_rerank", config(24, 8, 0.1D, true, 1_000)),
                Map.of()
        ));
        httpClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper();
        rerankClient = new RerankClient(databaseRerankConfigResolver, httpClient, objectMapper);
    }

    @Test
    void rerank_Success_UsesDatabaseTruthAndReturnsScoredDocuments() throws Exception {
        HttpResponse<String> response = mockSuccessResponse("""
                {
                  "model": "Qwen3-Reranker-8B",
                  "usage": {"prompt_tokens": 18, "total_tokens": 26},
                  "results": [
                    {"index": 1, "relevance_score": 0.87},
                    {"index": 0, "relevance_score": 0.42}
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var rerankResponse = rerankClient.rerank("what is java", List.of("doc-0", "doc-1"));
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        assertThat(rerankResponse.model()).isEqualTo("Qwen3-Reranker-8B");
        assertThat(rerankResponse.promptTokens()).isEqualTo(18);
        assertThat(rerankResponse.totalTokens()).isEqualTo(26);
        assertThat(rerankResponse.usage()).isNotNull();
        assertThat(rerankResponse.usage().usageFamily()).isEqualTo(UsageFamily.RERANK);
        assertThat(rerankResponse.usage().requestCount()).isEqualTo(1L);
        assertThat(rerankResponse.usage().promptTokens()).isEqualTo(18L);
        assertThat(rerankResponse.usage().totalTokens()).isEqualTo(26L);
        assertThat(rerankResponse.results()).hasSize(2);
        assertThat(rerankResponse.results().get(0).index()).isEqualTo(1);
        assertThat(rerankResponse.results().get(0).relevanceScore()).isEqualTo(0.87D);
        assertThat(requestCaptor.getValue().uri()).isEqualTo(URI.create("https://db-rerank.example.com/v1/rerank"));
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).contains("Bearer db-secret-key");
        assertThat(rerankClient.providerKey()).isEqualTo("db-rerank");
    }

    @Test
    void rerank_EmptyDocuments_ReturnEmptyResults() throws Exception {
        var response = rerankClient.rerank("query", List.of());

        assertThat(response.results()).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void rerank_Timeout_ThrowsRerankException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timeout"));

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void rerank_ExceedsMaxDocuments_ThrowsIllegalArgumentException() {
        databaseRerankConfigResolver = new DatabaseRerankConfigResolver(() -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("kb_query_rerank", config(1, 1, 0.1D, true, 1_000)),
                Map.of()
        ));
        rerankClient = new RerankClient(databaseRerankConfigResolver, httpClient, objectMapper);

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc1", "doc2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <= preRerankCandidateCap");
    }

    @Test
    void rerank_UpstreamError_ThrowsRerankException() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn("{\"error\":\"upstream failed\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
                .isInstanceOf(RerankException.class)
                .hasMessageContaining("status=502");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockSuccessResponse(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private DatabaseRerankConfig config(
            int preRerankCandidateCap,
            int stage1TopN,
            double stage1RelevanceThreshold,
            boolean dualQueryEnabled,
            int timeoutMs
    ) {
        return new DatabaseRerankConfig(
                "kb_query_rerank",
                "db-rerank",
                "https://db-rerank.example.com/v1/rerank",
                "db-secret-key",
                "Qwen3-Reranker-8B",
                timeoutMs,
                preRerankCandidateCap,
                stage1TopN,
                stage1RelevanceThreshold,
                dualQueryEnabled
        );
    }
}
