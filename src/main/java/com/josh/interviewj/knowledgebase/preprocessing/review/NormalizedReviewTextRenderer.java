package com.josh.interviewj.knowledgebase.preprocessing.review;

import org.springframework.stereotype.Component;

@Component
public class NormalizedReviewTextRenderer {

    public String render(ReviewProjection projection) {
        if (projection == null || projection.blocks().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (ReviewTextBlock block : projection.blocks()) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("[BLOCK ")
                    .append("blockOrder=").append(block.blockOrder())
                    .append(" type=").append(block.type())
                    .append(" disposition=").append(block.disposition().name())
                    .append(" page=").append(block.page() == null ? "unknown" : block.page());
            if (block.disposition() == ReviewBlockDisposition.REVIEW_ONLY) {
                if (block.reason() == null) {
                    throw new IllegalArgumentException("REVIEW_ONLY blocks must include reason");
                }
                builder.append(" reason=").append(block.reason().name());
            }
            builder.append("]\n")
                    .append(block.text())
                    .append("\n[/BLOCK]");
        }
        return builder.toString();
    }
}
