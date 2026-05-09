package com.josh.interviewj.llm.gateway.dto;

import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.ragqa.model.RerankResponse;

import java.util.Map;

public record AiInvocationResult(
        AiInvocationKind kind,
        Object response,
        String provider,
        String model,
        ProviderUsage usage,
        Map<String, Object> metadata
) {

    public static AiInvocationResult fromChat(LlmResponse response) {
        return new AiInvocationResult(
                AiInvocationKind.CHAT,
                response,
                response.provider(),
                response.model(),
                response.usage(),
                Map.of()
        );
    }

    public static AiInvocationResult fromChat(LlmResponse response, Map<String, Object> metadata) {
        return new AiInvocationResult(
                AiInvocationKind.CHAT,
                response,
                response.provider(),
                response.model(),
                response.usage(),
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    public static AiInvocationResult fromEmbedding(EmbeddingResponse response) {
        return new AiInvocationResult(
                AiInvocationKind.EMBEDDING,
                response,
                response.provider(),
                response.model(),
                response.usage(),
                Map.of()
        );
    }

    public static AiInvocationResult fromRerank(String provider, RerankResponse response) {
        return new AiInvocationResult(
                AiInvocationKind.RERANK,
                response,
                provider,
                response.model(),
                response.usage(),
                Map.of()
        );
    }

    public LlmResponse llmResponse() {
        return (LlmResponse) response;
    }

    public EmbeddingResponse embeddingResponse() {
        return (EmbeddingResponse) response;
    }

    public RerankResponse rerankResponse() {
        return (RerankResponse) response;
    }
}