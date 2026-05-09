package com.josh.interviewj.interview.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubmitInterviewAnswerRequest {

    @NotBlank(message = "answerContent is required")
    @Size(max = 20000, message = "answerContent must not exceed 20000 characters")
    private String answerContent;

    @Min(value = 1, message = "durationSeconds must be at least 1")
    private Integer durationSeconds;
}
