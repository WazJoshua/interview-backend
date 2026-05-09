package com.josh.interviewj.knowledgebase.preprocessing.review;

import lombok.Builder;

@Builder(toBuilder = true)
public record ReviewTextBlock(
        int blockOrder,
        String type,
        ReviewBlockDisposition disposition,
        Integer page,
        ReviewOnlyReasonCode reason,
        String text
) {

    public ReviewTextBlock {
        type = type == null ? "unknown" : type;
        disposition = disposition == null ? ReviewBlockDisposition.INDEX : disposition;
        text = text == null ? "" : text;
    }
}
