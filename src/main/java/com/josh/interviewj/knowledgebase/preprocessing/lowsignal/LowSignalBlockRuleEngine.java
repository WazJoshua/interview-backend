package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class LowSignalBlockRuleEngine {

    private final DocumentPreprocessingProperties properties;
    private final LowSignalProtectionRules protectionRules;
    private final LowSignalStrongDropRules strongDropRules;
    private final LowSignalScoreRules scoreRules;

    public LowSignalBlockDecision evaluate(NormalizedBlock block, DocumentPreprocessingProperties.ProfileProperties profile) {
        LowSignalBlockFeatures features = extractFeatures(block);
        LowSignalBlockContext context = LowSignalBlockContext.builder()
                .block(block)
                .features(features)
                .profile(profile)
                .appendixProperties(properties.getLowSignal().getAppendix())
                .build();
        java.util.List<LowSignalReasonCode> protectedReasons = protectionRules.evaluate(context);
        if (!protectedReasons.isEmpty()) {
            return LowSignalBlockDecision.fromLegacyDecision(LowSignalDecisionType.PROTECT, protectedReasons, 0.0D);
        }
        java.util.List<LowSignalReasonCode> droppedReasons = strongDropRules.evaluate(context);
        if (!droppedReasons.isEmpty()) {
            return LowSignalBlockDecision.fromLegacyDecision(LowSignalDecisionType.DROP, droppedReasons, 1.0D);
        }
        return scoreRules.evaluate(context);
    }

    private LowSignalBlockFeatures extractFeatures(NormalizedBlock block) {
        String text = block.text() == null ? "" : block.text();
        int symbolCount = (int) text.chars().filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)).count();
        int nonReadableCount = (int) text.chars().filter(ch -> Character.isISOControl(ch) && !Character.isWhitespace(ch)).count();
        int naturalLanguageCount = (int) text.chars().filter(Character::isLetter).count();
        int alphaNumericCount = (int) text.chars().filter(Character::isLetterOrDigit).count();
        int hexLikeCount = (int) text.chars()
                .filter(ch -> Character.isDigit(ch)
                        || (ch >= 'a' && ch <= 'f')
                        || (ch >= 'A' && ch <= 'F'))
                .count();
        String[] lines = text.split("\\R");
        int literalRun = 0;
        int maxLiteralLineLength = 0;
        int currentRun = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() >= properties.getLowSignal().getAppendix().getMinLiteralLineLength()
                    && trimmed.matches(".*[=:;{}\\[\\]/\\\\].*")) {
                currentRun++;
                maxLiteralLineLength = Math.max(maxLiteralLineLength, trimmed.length());
            } else {
                literalRun = Math.max(literalRun, currentRun);
                currentRun = 0;
            }
        }
        literalRun = Math.max(literalRun, currentRun);
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasSectionKeyword = properties.getLowSignal().getAppendix().getSectionKeywords().stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains)
                || block.sectionPath().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(lowerPath ->
                properties.getLowSignal().getAppendix().getSectionKeywords().stream()
                        .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                        .anyMatch(lowerPath::contains));
        boolean payloadSplitBlock = Objects.equals("payload", block.metadata().get("payloadSplitRole"));

        return LowSignalBlockFeatures.builder()
                .textLength(text.length())
                .symbolRatio(text.isEmpty() ? 0.0D : (double) symbolCount / text.length())
                .nonReadableRatio(text.isEmpty() ? 0.0D : (double) nonReadableCount / text.length())
                .naturalLanguageRatio(text.isEmpty() ? 0.0D : (double) naturalLanguageCount / text.length())
                .hexLikeRatio(alphaNumericCount == 0 ? 0.0D : (double) hexLikeCount / alphaNumericCount)
                .literalLineRun(literalRun)
                .maxLiteralLineLength(maxLiteralLineLength)
                .hasSectionKeyword(hasSectionKeyword)
                .payloadSplitBlock(payloadSplitBlock)
                .hasErrorCode(protectionRules.hasErrorCode(text))
                .hasPathOrUrl(protectionRules.hasPathOrUrl(text))
                .hasCommand(protectionRules.hasCommand(text))
                .build();
    }
}
