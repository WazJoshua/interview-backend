package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

public class DocumentPreprocessingException extends RuntimeException {

    public DocumentPreprocessingException(String message) {
        super(message);
    }

    public DocumentPreprocessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
