package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import lombok.Builder;

@Builder(toBuilder = true)
public record StepMetric(
        int inputBlockCount,
        int outputBlockCount,
        int removedCount,
        int warnedCount,
        int protectedCount
) {

    public static StepMetric empty(int blockCount) {
        return StepMetric.builder()
                .inputBlockCount(blockCount)
                .outputBlockCount(blockCount)
                .build();
    }
}
