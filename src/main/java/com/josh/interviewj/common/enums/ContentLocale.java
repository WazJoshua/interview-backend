package com.josh.interviewj.common.enums;

import java.util.Arrays;

/**
 * Supported user content locales and prompt-facing language labels.
 */
public enum ContentLocale {

    ZH_CN("zh-CN", "Simplified Chinese"),
    EN_US("en-US", "English");

    public static final ContentLocale DEFAULT = ZH_CN;

    private final String tag;
    private final String promptLanguage;

    ContentLocale(String tag, String promptLanguage) {
        this.tag = tag;
        this.promptLanguage = promptLanguage;
    }

    public String getTag() {
        return tag;
    }

    public String getPromptLanguage() {
        return promptLanguage;
    }

    public static ContentLocale fromTag(String tag) {
        if (tag == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(locale -> locale.tag.equals(tag))
                .findFirst()
                .orElse(null);
    }

    public static boolean isSupported(String tag) {
        return fromTag(tag) != null;
    }

    public static String normalizeOrDefault(String tag) {
        ContentLocale locale = fromTag(tag);
        return locale == null ? DEFAULT.tag : locale.tag;
    }

    public static ContentLocale resolveOrDefault(String tag) {
        ContentLocale locale = fromTag(tag);
        return locale == null ? DEFAULT : locale;
    }
}
