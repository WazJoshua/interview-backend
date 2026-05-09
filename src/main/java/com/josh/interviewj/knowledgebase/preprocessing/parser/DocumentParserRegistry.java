package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public boolean supports(String fileType, String fileName) {
        return parsers.stream().anyMatch(parser -> parser.supports(fileType, fileName));
    }

    public Optional<DocumentParser> findParser(String fileType, String fileName) {
        return parsers.stream().filter(parser -> parser.supports(fileType, fileName)).findFirst();
    }

    public DocumentParser requireParser(String fileType, String fileName) {
        return findParser(fileType, fileName)
                .orElseThrow(() -> new DocumentPreprocessingException("当前文档类型暂不支持结构化预处理"));
    }
}
