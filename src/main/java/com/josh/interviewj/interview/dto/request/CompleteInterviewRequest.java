package com.josh.interviewj.interview.dto.request;

import com.josh.interviewj.interview.model.InterviewCompletionReason;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompleteInterviewRequest {

    @NotNull(message = "completionReason is required")
    private InterviewCompletionReason completionReason;
}
