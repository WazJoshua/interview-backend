package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class KbDocumentDetailResponse {

    private UUID id;

    private UUID kbId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private Integer chunkCount;

    private Integer expectedChunkCount;

    private Integer embeddedChunkCount;

    private ChunkStrategy chunkStrategy;

    private KbDocumentStatus status;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime processedAt;

    private KbDocumentArtifactResponse artifactSummary;
}
