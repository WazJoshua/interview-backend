package com.josh.interviewj.knowledgebase.preprocessing.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedReviewTextRendererTest {

    @Test
    void render_MixedIndexAndReviewOnlyBlocks_UsesStableMarkerProtocol() {
        NormalizedReviewTextRenderer renderer = new NormalizedReviewTextRenderer();
        ReviewProjection projection = ReviewProjection.builder()
                .blocks(List.of(
                        ReviewTextBlock.builder()
                                .blockOrder(18)
                                .type("paragraph")
                                .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                                .page(7)
                                .reason(ReviewOnlyReasonCode.ORPHAN_HEX_PAYLOAD)
                                .text("payload")
                                .build(),
                        ReviewTextBlock.builder()
                                .blockOrder(12)
                                .type("paragraph")
                                .disposition(ReviewBlockDisposition.INDEX)
                                .page(6)
                                .text("正文")
                                .build()
                ))
                .build();

        String rendered = renderer.render(projection);

        assertEquals("""
                [BLOCK blockOrder=12 type=paragraph disposition=INDEX page=6]
                正文
                [/BLOCK]

                [BLOCK blockOrder=18 type=paragraph disposition=REVIEW_ONLY page=7 reason=ORPHAN_HEX_PAYLOAD]
                payload
                [/BLOCK]""", rendered);
    }
}
