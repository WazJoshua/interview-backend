package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;

import java.nio.file.Path;
import java.util.List;

public interface DocumentPreprocessingPipeline {

    NormalizedDocument preprocess(Path filePath, String fileType, String fileName);

    List<DocumentCleaningStep> getSteps();
}
