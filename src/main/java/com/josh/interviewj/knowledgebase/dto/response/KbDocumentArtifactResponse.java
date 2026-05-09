package com.josh.interviewj.knowledgebase.dto.response;

import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class KbDocumentArtifactResponse {

    private KbDocumentArtifactType artifactType;

    private Boolean exists;

    private String content;

    private Map<String, Object> metadata;

    private LocalDateTime updatedAt;
}
