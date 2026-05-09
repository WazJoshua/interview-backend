package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * KB document response payload.
 */
@Data
@Builder
public class KbDocumentResponse {

    private UUID id;

    private UUID kbId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private Integer chunkCount;

    private ChunkStrategy chunkStrategy;

    private KbDocumentStatus status;

    private String errorMessage;

    private JsonNode metadata;

    private LocalDateTime createdAt;

    private LocalDateTime processedAt;
}
