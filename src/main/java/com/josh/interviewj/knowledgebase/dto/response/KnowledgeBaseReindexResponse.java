package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Accepted snapshot returned when a knowledge base reindex request is submitted.
 */
@Data
@Builder
public class KnowledgeBaseReindexResponse {

    private UUID kbId;
    private Integer totalDocuments;
    private String status;
    private KnowledgeBaseIndexingStatus indexingStatus;
}
