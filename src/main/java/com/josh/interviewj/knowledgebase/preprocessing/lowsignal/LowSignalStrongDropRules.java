package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LowSignalStrongDropRules {

    public List<LowSignalReasonCode> evaluate(LowSignalBlockContext context) {
        String text = context.block().text().trim();
        if (text.matches("^[\\-_=*~.]{3,}$")) {
            return List.of(LowSignalReasonCode.DROP_SEPARATOR_PATTERN);
        }
        if (text.toLowerCase().matches("^(page\\s+)?[-(\\[]?\\d{1,4}[)\\].-]?$")) {
            return List.of(LowSignalReasonCode.DROP_PAGE_NUMBER);
        }
        if (text.matches("(?is).+\\.{3,}\\s*\\d{1,4}$")) {
            return List.of(LowSignalReasonCode.DROP_TOC_FRAGMENT);
        }
        if (text.length() >= 20 && context.features().nonReadableRatio() > 0.65D) {
            return List.of(LowSignalReasonCode.DROP_LOW_READABILITY);
        }
        return List.of();
    }
}
