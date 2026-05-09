package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight resume item returned by listing APIs.
 */
@Data
@Builder
public class ResumeResponseDTO {

    private UUID id;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String targetJob;

    private ResumeStatus status;

    private AnalysisStatus analysisStatus;

    private Boolean hasAnalysis;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
