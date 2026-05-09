package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

public interface DocumentCleaningStep {

    String getName();

    PreprocessingContext apply(PreprocessingContext context);
}
