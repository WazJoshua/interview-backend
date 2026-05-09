package com.josh.interviewj.knowledgebase.preprocessing.review;

import lombok.Builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Builder(toBuilder = true)
public record ReviewProjection(
        List<ReviewTextBlock> blocks
) {

    public ReviewProjection {
        List<ReviewTextBlock> safeBlocks = blocks == null ? List.of() : List.copyOf(blocks);
        blocks = safeBlocks.stream()
                .sorted(Comparator.comparingInt(ReviewTextBlock::blockOrder))
                .toList();
    }

    public ReviewProjection append(List<ReviewTextBlock> additionalBlocks) {
        List<ReviewTextBlock> merged = new ArrayList<>(blocks);
        merged.addAll(additionalBlocks);
        return new ReviewProjection(merged);
    }
}
