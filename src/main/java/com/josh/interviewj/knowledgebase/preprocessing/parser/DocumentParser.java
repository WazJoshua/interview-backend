package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;

import java.nio.file.Path;

public interface DocumentParser {

    boolean supports(String fileType, String fileName);

    ParsedDocument parse(Path filePath, String fileType, String fileName);
}
