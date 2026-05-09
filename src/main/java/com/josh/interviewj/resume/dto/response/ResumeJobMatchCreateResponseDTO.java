package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.AnalysisStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Response payload for match report creation.
 */
@Data
@Builder
public class ResumeJobMatchCreateResponseDTO {

    private Long matchReportId;

    private AnalysisStatus status;
}

