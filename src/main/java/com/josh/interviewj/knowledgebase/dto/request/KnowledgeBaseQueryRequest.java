package com.josh.interviewj.knowledgebase.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Query parameters for paginated knowledge base listing.
 */
@Data
public class KnowledgeBaseQueryRequest {

    @Min(value = 0, message = "Page must be greater than or equal to 0")
    private Integer page = 0;

    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size must not exceed 100")
    private Integer size = 20;

    private String sort = "createdAt,desc";

    @Pattern(regexp = "ACTIVE|ARCHIVED", message = "status must be ACTIVE or ARCHIVED")
    private String status;
}
