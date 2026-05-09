package com.josh.interviewj.resume.prompt;

import com.josh.interviewj.common.enums.ContentLocale;

/**
 * Centralized prompt wording for locale-driven user-facing content.
 */
public final class ContentLocalePromptSupport {

    private ContentLocalePromptSupport() {
    }

    public static String resolvePromptLanguage(String contentLocale) {
        return ContentLocale.resolveOrDefault(contentLocale).getPromptLanguage();
    }

    public static String buildUserFacingOutputInstruction(String contentLocale, String fieldsDescription) {
        String promptLanguage = resolvePromptLanguage(contentLocale);
        return "All user-facing text in " + fieldsDescription + " must be written in " + promptLanguage + ".";
    }
}
