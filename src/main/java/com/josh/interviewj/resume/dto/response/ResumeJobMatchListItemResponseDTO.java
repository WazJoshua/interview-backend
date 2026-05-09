package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * List item for match reports.
 */
@Data
@Builder
public class ResumeJobMatchListItemResponseDTO {

    private Long matchReportId;

    private AnalysisStatus status;

    private String contentLocale;

    private Integer matchScore;

    private String summary;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
