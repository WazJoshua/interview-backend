package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Live aggregation snapshot for a knowledge base.
 */
@Data
@Builder
public class KnowledgeBaseStatsResponse {

    private Integer totalDocuments;
    private Integer totalChunks;
    private Long totalSize;
    private Integer pendingDocuments;
    private Integer processingDocuments;
    private Integer failedDocuments;
    private Long totalQueries;
    private Double averageConfidence;
    private KnowledgeBaseIndexingStatus indexingStatus;
}
