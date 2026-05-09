package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.service.KbEmbeddingService;
import com.josh.interviewj.llm.core.EmbeddingClient;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbEmbeddingServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    private KbEmbeddingService kbEmbeddingService;

    @BeforeEach
    void setUp() {
        kbEmbeddingService = new KbEmbeddingService(embeddingClient);
    }

    @Test
    void embedQuery_DelegatesUsingKbQueryEmbeddingPurpose() {
        when(embeddingClient.generate(any())).thenReturn(new EmbeddingResponse(new float[]{0.1f, 0.2f}, "default", "text-embedding-v4"));
        float[] result = kbEmbeddingService.embedQuery("Redis persistence?");

        ArgumentCaptor<EmbeddingRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingClient).generate(requestCaptor.capture());
        assertEquals("kb_query_embedding", requestCaptor.getValue().purpose());
        assertEquals("Redis persistence?", requestCaptor.getValue().input());
        assertArrayEquals(new float[]{0.1f, 0.2f}, result);
    }

    @Test
    void embedDocument_DelegatesUsingKbDocumentEmbeddingPurpose() {
        when(embeddingClient.generate(any())).thenReturn(new EmbeddingResponse(new float[]{0.3f, 0.4f}, "default", "text-embedding-v4"));
        float[] result = kbEmbeddingService.embedDocument("Document chunk");

        ArgumentCaptor<EmbeddingRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingClient).generate(requestCaptor.capture());
        assertEquals("kb_document_embedding", requestCaptor.getValue().purpose());
        assertEquals("Document chunk", requestCaptor.getValue().input());
        assertArrayEquals(new float[]{0.3f, 0.4f}, result);
    }

    @Test
    void embed_WhenInputBlank_ThrowsLlm001() {
        BusinessException exception = assertThrows(BusinessException.class, () -> kbEmbeddingService.embedQuery("  "));

        assertEquals("LLM_001", exception.getErrorCode());
        assertEquals("Embedding input is required", exception.getMessage());
    }
}
