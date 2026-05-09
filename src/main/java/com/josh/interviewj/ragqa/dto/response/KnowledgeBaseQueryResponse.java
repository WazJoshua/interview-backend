package com.josh.interviewj.ragqa.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Knowledge base query response payload.
 */
@Data
@Builder
public class KnowledgeBaseQueryResponse {

    private String answer;

    private UUID chatSessionId;

    private UUID userMessageId;

    private UUID assistantMessageId;

    private List<Source> sources;

    private Double confidence;

    private Integer retrievedChunkCount;

    private Long processingTime;

    /**
     * Query source entry.
     */
    @Data
    @Builder
    public static class Source {

        private UUID documentId;

        private String documentName;

        private Integer chunkIndex;

        private String content;

        private Double similarity;
    }
}
