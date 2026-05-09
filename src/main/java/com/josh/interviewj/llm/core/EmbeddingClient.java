package com.josh.interviewj.llm.core;

public interface EmbeddingClient {

    EmbeddingResponse generate(EmbeddingRequest request);
}
