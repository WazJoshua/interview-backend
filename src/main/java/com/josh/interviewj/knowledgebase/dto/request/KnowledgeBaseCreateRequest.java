package com.josh.interviewj.knowledgebase.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request payload for creating a knowledge base.
 */
@Data
public class KnowledgeBaseCreateRequest {

    @NotBlank(message = "Knowledge base name is required")
    @Size(max = 200, message = "Knowledge base name must not exceed 200 characters")
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @Size(max = 100, message = "Embedding model must not exceed 100 characters")
    private String embeddingModel;
}
