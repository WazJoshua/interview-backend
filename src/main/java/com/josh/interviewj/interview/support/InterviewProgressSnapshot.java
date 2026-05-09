package com.josh.interviewj.interview.support;

import java.util.UUID;

public record InterviewProgressSnapshot(
        Integer mainQuestionCount,
        Integer answeredMainQuestionCount,
        UUID currentQuestionId,
        String currentQuestionKind,
        Integer currentBranchDepth,
        Integer usedFollowUpCount,
        Integer pendingFollowUpCount,
        Boolean isCompletable
) {
}
