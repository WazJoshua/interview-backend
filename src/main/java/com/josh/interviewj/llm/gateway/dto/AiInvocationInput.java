package com.josh.interviewj.llm.gateway.dto;

import java.util.List;
import java.util.function.Consumer;

public record AiInvocationInput(
        AiInvocationKind kind,
        String systemPrompt,
        String userPrompt,
        Consumer<String> schemaValidator,
        String embeddingInput,
        String rerankQuery,
        List<String> rerankDocuments,
        PromptTemplateRef promptTemplateRef
) {

    public AiInvocationInput {
        rerankDocuments = rerankDocuments == null ? List.of() : List.copyOf(rerankDocuments);
    }

    /**
     * Original chat factory method - backward compatible, no template ref.
     */
    public static AiInvocationInput chat(String systemPrompt, String userPrompt, Consumer<String> schemaValidator) {
        return new AiInvocationInput(AiInvocationKind.CHAT, systemPrompt, userPrompt, schemaValidator, null, null, List.of(), null);
    }

    /**
     * New chat factory method with prompt template reference.
     * Both fallback prompts and template ref are provided - executor will try template first, fallback on failure.
     */
    public static AiInvocationInput chat(String systemPrompt, String userPrompt, Consumer<String> schemaValidator, PromptTemplateRef promptTemplateRef) {
        return new AiInvocationInput(AiInvocationKind.CHAT, systemPrompt, userPrompt, schemaValidator, null, null, List.of(), promptTemplateRef);
    }

    public static AiInvocationInput embedding(String input) {
        return new AiInvocationInput(AiInvocationKind.EMBEDDING, null, null, null, input, null, List.of(), null);
    }

    public static AiInvocationInput rerank(String query, List<String> documents) {
        return new AiInvocationInput(AiInvocationKind.RERANK, null, null, null, null, query, documents, null);
    }
}