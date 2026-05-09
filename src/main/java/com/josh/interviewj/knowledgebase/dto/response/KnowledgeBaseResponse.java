package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Knowledge base response payload.
 */
@Data
@Builder
public class KnowledgeBaseResponse {

    private UUID id;

    private String name;

    private String description;

    private String embeddingModel;

    private Integer vectorDimension;

    private Integer documentCount;

    private Integer totalChunks;

    private Integer version;

    private Boolean isPublic;

    private KnowledgeBaseStatus status;

    private KnowledgeBaseIndexingStatus indexingStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
