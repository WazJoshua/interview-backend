package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.executor.RerankInvocationExecutor;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.ragqa.service.RerankClient;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankInvocationExecutorTest {

    @Test
    void execute_DelegatesPurposeQueryAndDocuments() {
        RerankClient rerankClient = mock(RerankClient.class);
        RerankInvocationExecutor executor = new RerankInvocationExecutor(rerankClient);
        RerankResponse response = new RerankResponse(
                "rerank-model",
                12,
                List.of(new RerankResponse.ScoredDocument(0, 0.9)),
                12,
                new ProviderUsage(UsageFamily.RERANK, 1L, 12L, null, 12L, null)
        );
        when(rerankClient.rerank("kb_query_rerank", "q", List.of("a", "b"))).thenReturn(response);
        when(rerankClient.providerKey("kb_query_rerank")).thenReturn("default");

        AiInvocationResult result = executor.execute(
                new AiInvocationContext(
                        "inv-1",
                        "kb_query_rerank",
                        UsageFamily.RERANK,
                        "KB_QUERY_CREDITS",
                        false,
                        Map.of()
                ),
                AiInvocationInput.rerank("q", List.of("a", "b"))
        );

        assertThat(result.provider()).isEqualTo("default");
        assertThat(result.model()).isEqualTo("rerank-model");
        assertThat(result.rerankResponse().results()).hasSize(1);
        verify(rerankClient).rerank("kb_query_rerank", "q", List.of("a", "b"));
    }
}
