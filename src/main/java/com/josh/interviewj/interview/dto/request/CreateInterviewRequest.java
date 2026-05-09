package com.josh.interviewj.interview.dto.request;

import com.josh.interviewj.interview.model.InterviewMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateInterviewRequest {

    private UUID resumeId;

    @NotBlank(message = "jobTitle is required")
    @Size(max = 200, message = "jobTitle must not exceed 200 characters")
    private String jobTitle;

    @Size(max = 10000, message = "jobDescription must not exceed 10000 characters")
    private String jobDescription;

    @Pattern(
            regexp = "^(JUNIOR|MID|SENIOR)$",
            message = "difficultyLevel must be one of JUNIOR, MID, SENIOR"
    )
    private String difficultyLevel;

    @Min(value = 1, message = "durationMinutes must be at least 1")
    private Integer durationMinutes;

    private InterviewMode interviewMode = InterviewMode.TEXT;
}
