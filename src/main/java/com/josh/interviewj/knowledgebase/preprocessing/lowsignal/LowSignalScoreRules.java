package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LowSignalScoreRules {

    public LowSignalBlockDecision evaluate(LowSignalBlockContext context) {
        List<LowSignalReasonCode> reasons = new ArrayList<>();
        double score = 0.0D;

        if (context.features().textLength() < 15) {
            reasons.add(LowSignalReasonCode.WARN_SHORT_FRAGMENT);
            score += 0.35D;
        }
        if (context.features().symbolRatio() > 0.5D) {
            reasons.add(LowSignalReasonCode.WARN_HIGH_SYMBOL_RATIO);
            score += 0.40D;
        }
        if (isAppendixSample(context)) {
            reasons.add(LowSignalReasonCode.WARN_POSSIBLE_APPENDIX_SAMPLE_DATA);
            if (context.profile() != null && context.profile().isDropAppendixSamples()) {
                return LowSignalBlockDecision.fromLegacyDecision(LowSignalDecisionType.DROP, reasons, 1.0D);
            }
            return LowSignalBlockDecision.fromLegacyDecision(
                    LowSignalDecisionType.WARN,
                    reasons,
                    Math.max(score, 0.8D)
            );
        }
        if (!reasons.isEmpty()) {
            return LowSignalBlockDecision.fromLegacyDecision(LowSignalDecisionType.WARN, reasons, score);
        }
        return LowSignalBlockDecision.fromLegacyDecision(LowSignalDecisionType.KEEP, List.of(), score);
    }

    private boolean isAppendixSample(LowSignalBlockContext context) {
        LowSignalBlockFeatures features = context.features();
        int minimumLiteralLineRun = features.hasSectionKeyword()
                ? Math.max(4, context.appendixProperties().getMinLiteralLineRun() - 4)
                : context.appendixProperties().getMinLiteralLineRun();
        if (features.payloadSplitBlock()) {
            minimumLiteralLineRun = Math.min(minimumLiteralLineRun, 4);
        }
        double maxNaturalLanguageRatio = features.hasSectionKeyword()
                ? Math.max(context.appendixProperties().getMaxNaturalLanguageRatio(), 0.35D)
                : context.appendixProperties().getMaxNaturalLanguageRatio();
        boolean hexHeavyPayload = features.hexLikeRatio() >= 0.55D && features.hasSectionKeyword();
        return (features.literalLineRun() >= minimumLiteralLineRun || features.payloadSplitBlock())
                && features.maxLiteralLineLength() >= context.appendixProperties().getMinLiteralLineLength()
                && (features.payloadSplitBlock()
                        || features.naturalLanguageRatio() <= maxNaturalLanguageRatio
                        || hexHeavyPayload)
                && features.hasSectionKeyword();
    }
}
