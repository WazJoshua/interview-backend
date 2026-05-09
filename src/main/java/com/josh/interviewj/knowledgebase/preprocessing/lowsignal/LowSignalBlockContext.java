package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import lombok.Builder;

@Builder
public record LowSignalBlockContext(
        NormalizedBlock block,
        LowSignalBlockFeatures features,
        DocumentPreprocessingProperties.ProfileProperties profile,
        DocumentPreprocessingProperties.AppendixProperties appendixProperties
) {
}
