package com.josh.interviewj.resume.service;

import com.josh.interviewj.llm.core.LlmResponse;

public record StructuredExtractionResult(
        String parsedContent,
        LlmResponse llmResponse
) {
}
