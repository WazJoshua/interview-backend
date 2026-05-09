package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.EmbeddingService;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.executor.EmbeddingInvocationExecutor;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingInvocationExecutorTest {

    @Test
    void execute_DelegatesPurposeAndInput() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EmbeddingInvocationExecutor executor = new EmbeddingInvocationExecutor(embeddingService);
        EmbeddingResponse response = new EmbeddingResponse(
                new float[]{1F, 2F},
                "default",
                "text-embedding",
                new ProviderUsage(UsageFamily.EMBEDDING, 1L, 5L, null, 5L, null)
        );
        when(embeddingService.generate(any())).thenReturn(response);

        AiInvocationResult result = executor.execute(
                new AiInvocationContext(
                        "inv-1",
                        "kb_query_embedding",
                        UsageFamily.EMBEDDING,
                        "KB_QUERY_CREDITS",
                        false,
                        Map.of()
                ),
                AiInvocationInput.embedding("hello")
        );

        assertThat(result.provider()).isEqualTo("default");
        assertThat(result.model()).isEqualTo("text-embedding");
        assertThat(result.embeddingResponse().vector()).containsExactly(1F, 2F);
        verify(embeddingService).generate(any());
    }
}
