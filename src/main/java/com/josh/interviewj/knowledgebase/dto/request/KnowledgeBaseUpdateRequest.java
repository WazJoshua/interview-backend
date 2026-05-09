package com.josh.interviewj.knowledgebase.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Partial update payload for mutable knowledge base fields.
 */
@Data
public class KnowledgeBaseUpdateRequest {

    @Size(max = 200, message = "name must not exceed 200 characters")
    private String name;

    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;

    /**
     * Ensures that the payload carries at least one visible mutable field.
     *
     * @return {@code true} when name or description is provided
     */
    @AssertTrue(message = "At least one updatable field must be provided")
    public boolean isAnyFieldProvided() {
        return hasText(name) || hasText(description);
    }

    /**
     * Exposes the same validation result to service code that performs defensive checks.
     *
     * @return {@code true} when name or description is provided
     */
    public boolean hasAnyFieldProvided() {
        return isAnyFieldProvided();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
