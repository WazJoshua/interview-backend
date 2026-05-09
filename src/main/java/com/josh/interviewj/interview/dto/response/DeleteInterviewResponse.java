package com.josh.interviewj.interview.dto.response;

import java.util.UUID;

public record DeleteInterviewResponse(
        UUID interviewId,
        boolean deleted
) {
}
