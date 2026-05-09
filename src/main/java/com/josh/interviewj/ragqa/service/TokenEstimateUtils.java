package com.josh.interviewj.ragqa.service;

public final class TokenEstimateUtils {

    private TokenEstimateUtils() {
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.length();
    }
}
