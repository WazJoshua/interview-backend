package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.LiteralSignalProfile;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LiteralSignalDetector {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\b[A-Z]{2,}_[0-9]{3,}\\b");
    private static final Pattern CONFIG_PATH_PATTERN = Pattern.compile("\\b[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,}\\b");
    private static final Pattern CLI_OPTION_PATTERN = Pattern.compile("(?<!\\S)--[a-z0-9][a-z0-9-]*\\b");
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    private static final Pattern CODE_SYMBOL_PATTERN = Pattern.compile("\\b(?:[A-Z][a-z0-9]+){2,}[A-Z]?[A-Za-z0-9]*\\b");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\b[vV]?\\d+(?:\\.\\d+){1,3}\\b");

    public LiteralSignalProfile detect(NormalizedQuery normalizedQuery) {
        Set<String> exactBoostTerms = new LinkedHashSet<>();
        Set<String> matchedSignalTypes = new LinkedHashSet<>();

        String normalizedText = normalizedQuery.normalizedText();
        detectPattern(normalizedText, ERROR_CODE_PATTERN, "ERROR_CODE", matchedSignalTypes, exactBoostTerms);
        detectPattern(normalizedText, CONFIG_PATH_PATTERN, "CONFIG_PATH", matchedSignalTypes, exactBoostTerms);
        detectPattern(normalizedText, CLI_OPTION_PATTERN, "CLI_OPTION", matchedSignalTypes, exactBoostTerms);
        detectPattern(normalizedText, ENV_VAR_PATTERN, "ENV_VAR", matchedSignalTypes, exactBoostTerms);
        detectPattern(normalizedText, CODE_SYMBOL_PATTERN, "CODE_SYMBOL", matchedSignalTypes, exactBoostTerms);
        detectPattern(normalizedText, VERSION_PATTERN, "VERSION_TOKEN", matchedSignalTypes, exactBoostTerms);

        normalizedQuery.preservedTokens().forEach(exactBoostTerms::add);
        normalizedQuery.protectedTerms().forEach(exactBoostTerms::add);

        boolean hasErrorCode = containsMatch(normalizedText, ERROR_CODE_PATTERN);
        boolean hasConfigPath = containsMatch(normalizedText, CONFIG_PATH_PATTERN);
        boolean hasCliOption = containsMatch(normalizedText, CLI_OPTION_PATTERN);
        boolean hasEnvVar = containsMatch(normalizedText, ENV_VAR_PATTERN);
        boolean hasCodeSymbol = containsMatch(normalizedText, CODE_SYMBOL_PATTERN);
        boolean hasVersionToken = containsMatch(normalizedText, VERSION_PATTERN);

        return LiteralSignalProfile.of(
                hasErrorCode,
                hasConfigPath,
                hasCliOption,
                hasEnvVar,
                hasCodeSymbol,
                hasVersionToken,
                List.copyOf(matchedSignalTypes),
                exactBoostTerms.size(),
                List.copyOf(exactBoostTerms)
        );
    }

    private void detectPattern(
            String text,
            Pattern pattern,
            String signalType,
            Set<String> matchedSignalTypes,
            Set<String> exactBoostTerms
    ) {
        Matcher matcher = pattern.matcher(text);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            exactBoostTerms.add(matcher.group());
        }
        if (matched) {
            matchedSignalTypes.add(signalType);
        }
    }

    private boolean containsMatch(String text, Pattern pattern) {
        return pattern.matcher(text).find();
    }
}
