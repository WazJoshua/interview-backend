package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ResumeDetailResponseDTO {

    private UUID id;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String targetJob;

    private ResumeStatus status;

    private AnalysisStatus analysisStatus;

    private Boolean hasAnalysis;

    private JsonNode parsedContent;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
