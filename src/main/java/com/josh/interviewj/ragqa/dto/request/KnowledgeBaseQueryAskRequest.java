package com.josh.interviewj.ragqa.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * Request payload for querying a knowledge base.
 */
@Data
public class KnowledgeBaseQueryAskRequest {

    @NotBlank(message = "Question is required")
    @Size(max = 5000, message = "Question must not exceed 5000 characters")
    private String question;

    @Min(value = 1, message = "topK must be at least 1")
    @Max(value = 20, message = "topK must not exceed 20")
    private Integer topK = 3;

    private Boolean includeSources = true;

    private UUID chatSessionId;
}
