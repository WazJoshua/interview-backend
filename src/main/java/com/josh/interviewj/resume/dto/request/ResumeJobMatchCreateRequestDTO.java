package com.josh.interviewj.resume.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request payload for creating a resume × JD match report.
 */
@Data
public class ResumeJobMatchCreateRequestDTO {

    @NotBlank(message = "Job title is required")
    @Size(max = 200, message = "Job title must not exceed 200 characters")
    private String jobTitle;

    @NotBlank(message = "Job description is required")
    @Size(max = 10000, message = "Job description must not exceed 10000 characters")
    private String jobDescription;
}

