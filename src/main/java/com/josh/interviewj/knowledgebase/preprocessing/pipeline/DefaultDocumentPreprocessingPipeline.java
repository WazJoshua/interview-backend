package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultDocumentPreprocessingPipeline implements DocumentPreprocessingPipeline {

    private final DocumentParserRegistry documentParserRegistry;
    private final DocumentPreprocessingProperties properties;
    private final List<DocumentCleaningStep> steps;

    @Override
    public NormalizedDocument preprocess(Path filePath, String fileType, String fileName) {
        ParsedDocument parsedDocument = documentParserRegistry.requireParser(fileType, fileName)
                .parse(filePath, fileType, fileName);
        DocumentPreprocessingProperties.ProfileProperties profile = properties.getProfiles()
                .getOrDefault("default", DocumentPreprocessingProperties.ProfileProperties.builder().dropAppendixSamples(false).build());
        PreprocessingContext context = PreprocessingContext.fromParsedDocument(parsedDocument, profile);
        for (DocumentCleaningStep step : steps) {
            context = step.apply(context);
        }
        Object normalizedDocument = context.documentMetadata().get("normalizedDocument");
        if (normalizedDocument instanceof NormalizedDocument document) {
            return document;
        }
        throw new DocumentPreprocessingException("文档预处理未能生成标准化结果");
    }

    @Override
    public List<DocumentCleaningStep> getSteps() {
        return List.copyOf(steps);
    }
}
