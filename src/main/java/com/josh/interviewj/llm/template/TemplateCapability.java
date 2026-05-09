package com.josh.interviewj.llm.template;

public enum TemplateCapability {
    CHAT("chat"),
    EMBEDDING("embedding");

    private final String folderName;

    TemplateCapability(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }
}
