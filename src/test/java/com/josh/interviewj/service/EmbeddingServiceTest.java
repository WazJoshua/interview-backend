package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.EmbeddingService;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.routing.EmbeddingRoute;
import com.josh.interviewj.llm.routing.EmbeddingRouter;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingRouter embeddingRouter;

    @Mock
    private TemplateAwareEmbeddingExecutor templateAwareEmbeddingExecutor;

    @Test
    void generate_KbQueryEmbedding_UsesRouteConfiguredTextType() {
        when(embeddingRouter.resolve("kb_query_embedding")).thenReturn(route("kb_query_embedding", "query-key", "query"));
        when(templateAwareEmbeddingExecutor.generateEmbedding(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(response(new float[]{0.1f, 0.2f}, 3L));

        EmbeddingService service = new EmbeddingService(embeddingRouter, templateAwareEmbeddingExecutor);
        EmbeddingResponse response = service.generate(new EmbeddingRequest("kb_query_embedding", "hello"));

        ArgumentCaptor<String> textTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(templateAwareEmbeddingExecutor).generateEmbedding(
                any(),
                any(),
                any(),
                any(),
                any(),
                textTypeCaptor.capture(),
                anyInt()
        );
        assertEquals("query", textTypeCaptor.getValue());
        assertArrayEquals(new float[]{0.1f, 0.2f}, response.vector());
        assertEquals(3L, response.usage().totalTokens());
    }

    @Test
    void generate_KbDocumentEmbedding_UsesRouteConfiguredTextType() {
        when(embeddingRouter.resolve("kb_document_embedding")).thenReturn(route("kb_document_embedding", "document-key", "passage"));
        when(templateAwareEmbeddingExecutor.generateEmbedding(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(response(new float[]{0.3f, 0.4f}, 5L));

        EmbeddingService service = new EmbeddingService(embeddingRouter, templateAwareEmbeddingExecutor);
        EmbeddingResponse response = service.generate(new EmbeddingRequest("kb_document_embedding", "world"));

        ArgumentCaptor<String> textTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(templateAwareEmbeddingExecutor).generateEmbedding(
                any(),
                any(),
                any(),
                any(),
                any(),
                textTypeCaptor.capture(),
                anyInt()
        );
        assertEquals("passage", textTypeCaptor.getValue());
        assertArrayEquals(new float[]{0.3f, 0.4f}, response.vector());
        assertEquals(5L, response.usage().totalTokens());
    }

    @Test
    void generate_MissingApiKey_ThrowsBusinessException() {
        when(embeddingRouter.resolve("kb_query_embedding")).thenReturn(route("kb_query_embedding", " ", "query"));

        EmbeddingService service = new EmbeddingService(embeddingRouter, templateAwareEmbeddingExecutor);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.generate(new EmbeddingRequest("kb_query_embedding", "hello"))
        );

        assertEquals("LLM_001", exception.getErrorCode());
    }

    private EmbeddingRoute route(String purpose, String apiKey, String inputType) {
        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setApiKey(apiKey);
        providerProperties.setBaseUrl("http://localhost:8080");
        providerProperties.setTimeoutMs(2000);
        providerProperties.setMaxRetries(1);
        providerProperties.setRetryBackoffMs(1);

        LlmProperties.EmbeddingProperties embeddingProperties = new LlmProperties.EmbeddingProperties();
        embeddingProperties.setDimension(2);
        providerProperties.setEmbedding(embeddingProperties);
        return new EmbeddingRoute("Nvidia", purpose, "text-embedding-v4", inputType, 2, providerProperties);
    }

    private EmbeddingResponse response(float[] vector, long totalTokens) {
        return new EmbeddingResponse(
                vector,
                "Nvidia",
                "text-embedding-v4",
                new ProviderUsage(UsageFamily.EMBEDDING, 1L, totalTokens, null, totalTokens, null)
        );
    }
}
