package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detailed match report response.
 */
@Data
@Builder
public class ResumeJobMatchDetailResponseDTO {

    private Long matchReportId;

    private String jobTitle;

    private String jobDescription;

    private AnalysisStatus status;

    private String contentLocale;

    private Integer matchScore;

    private String summary;

    private List<String> strengths;

    private List<String> gaps;

    private List<String> suggestions;

    private String promptVersion;

    private String modelName;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private String errorMessage;
}
