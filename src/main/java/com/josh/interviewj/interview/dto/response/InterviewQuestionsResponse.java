package com.josh.interviewj.interview.dto.response;

import java.util.List;

public record InterviewQuestionsResponse(
        List<InterviewQuestionItemResponse> questions
) {
}
