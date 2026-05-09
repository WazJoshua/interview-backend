package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.ragqa.model.LiteralSignalProfile;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryNormalizationService {

    private static final Pattern BACKTICK_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern STRUCTURED_PATTERN = Pattern.compile(
            "(?<!\\w)(?:/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*|[A-Za-z0-9]+(?:[._/-][A-Za-z0-9-]+)+)(?!\\w)"
    );
    private static final Pattern VERSIONED_TERM_PATTERN = Pattern.compile("(?<!\\w)[A-Za-z][A-Za-z#+-]*\\s+\\d+(?:\\.\\d+)?(?!\\w)");
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<!\\w)[A-Z][A-Za-z0-9]+(?:[A-Z][A-Za-z0-9]*)+(?!\\w)");
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("(?<!\\w)[A-Z]{2,}(?!\\w)");
    private static final Pattern MIXED_IDENTIFIER_PATTERN = Pattern.compile(
            "(?<!\\w)(?=[A-Za-z0-9._/-]*[A-Za-z])(?=[A-Za-z0-9._/-]*\\d)[A-Za-z][A-Za-z0-9._/-]*(?!\\w)"
    );

    private final QueryUnderstandingProperties properties;

    public QueryNormalizationService(QueryUnderstandingProperties properties) {
        this.properties = properties;
    }

    public NormalizedQuery normalize(String question) {
        String normalizedCore = normalizeSurface(question);
        List<String> preservedTokens = detectStructuredTokens(normalizedCore);
        List<String> aliasExpansions = resolveAliasExpansions(normalizedCore);

        String normalizedText = normalizedCore;
        if (!aliasExpansions.isEmpty()) {
            normalizedText = normalizedCore + " " + String.join(" ", aliasExpansions);
        }

        Set<String> protectedTerms = new LinkedHashSet<>(preservedTokens);
        extractBacktickedTerms(normalizedCore, protectedTerms);
        extractPatternTerms(normalizedCore, protectedTerms, CAMEL_CASE_PATTERN);
        extractPatternTerms(normalizedCore, protectedTerms, MIXED_IDENTIFIER_PATTERN);

        return new NormalizedQuery(
                question,
                normalizedText,
                preservedTokens,
                List.copyOf(protectedTerms),
                aliasExpansions,
                QueryProfile.none(),
                LiteralSignalProfile.none()
        );
    }

    static String normalizeSurface(String input) {
        if (input == null) {
            throw blankInput();
        }
        String trimmed = input.strip();
        if (trimmed.isBlank()) {
            throw blankInput();
        }

        String compressed = trimmed.replace('\u3000', ' ').replaceAll("\\s+", " ");
        return compressed
                .replace('，', ',')
                .replace('。', '.')
                .replace('？', '?')
                .replace('！', '!')
                .replace('：', ':')
                .replace('；', ';')
                .replace('（', '(')
                .replace('）', ')')
                .replace('【', '[')
                .replace('】', ']')
                .replace('「', '"')
                .replace('」', '"')
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('／', '/');
    }

    static String normalizeForComparison(String input) {
        return normalizeSurface(input).toLowerCase(Locale.ROOT);
    }

    private List<String> detectStructuredTokens(String text) {
        List<TokenMatch> matches = new ArrayList<>();
        collectMatches(text, STRUCTURED_PATTERN, matches);
        collectMatches(text, VERSIONED_TERM_PATTERN, matches);
        collectMatches(text, CAMEL_CASE_PATTERN, matches);
        collectMatches(text, ACRONYM_PATTERN, matches);
        matches.sort(Comparator.comparingInt(TokenMatch::start).thenComparing(Comparator.comparingInt(TokenMatch::length).reversed()));

        List<TokenMatch> accepted = new ArrayList<>();
        for (TokenMatch candidate : matches) {
            if (accepted.stream().noneMatch(existing -> overlaps(existing, candidate))) {
                accepted.add(candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(TokenMatch::start));
        return accepted.stream().map(TokenMatch::text).toList();
    }

    private void collectMatches(String text, Pattern pattern, List<TokenMatch> matches) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(new TokenMatch(matcher.start(), matcher.end(), matcher.group()));
        }
    }

    private boolean overlaps(TokenMatch left, TokenMatch right) {
        return left.start < right.end && right.start < left.end;
    }

    private List<String> resolveAliasExpansions(String normalizedText) {
        String comparisonText = normalizedText.toLowerCase(Locale.ROOT);
        List<AliasMatch> matches = new ArrayList<>();
        List<QueryUnderstandingProperties.AliasEntry> dictionary = properties.getAliasDictionary();

        for (int index = 0; index < dictionary.size(); index++) {
            QueryUnderstandingProperties.AliasEntry entry = dictionary.get(index);
            String normalizedAlias = normalizeSurface(entry.getAlias());
            String comparisonAlias = normalizedAlias.toLowerCase(Locale.ROOT);
            List<Range> ranges = findWholeMatches(comparisonText, comparisonAlias);
            if (ranges.isEmpty()) {
                continue;
            }
            boolean exactMatch = comparisonText.equals(comparisonAlias);
            matches.add(new AliasMatch(index, normalizedAlias, entry.getCanonical(), exactMatch, ranges));
        }

        matches.sort(Comparator
                .comparing((AliasMatch match) -> match.exactMatch() ? 0 : 1)
                .thenComparing(Comparator.comparingInt(AliasMatch::length).reversed())
                .thenComparingInt(AliasMatch::order));

        List<Range> consumed = new ArrayList<>();
        LinkedHashSet<String> expansions = new LinkedHashSet<>();
        for (AliasMatch match : matches) {
            if (match.ranges().stream().noneMatch(range -> overlaps(range, consumed))) {
                String canonical = normalizeSurface(match.canonical());
                String comparisonCanonical = canonical.toLowerCase(Locale.ROOT);
                if (!comparisonText.contains(comparisonCanonical)) {
                    expansions.add(canonical);
                }
                consumed.addAll(match.ranges());
            }
        }
        return List.copyOf(expansions);
    }

    private List<Range> findWholeMatches(String haystack, String needle) {
        List<Range> ranges = new ArrayList<>();
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            int end = index + needle.length();
            if (isBoundary(haystack, index - 1) && isBoundary(haystack, end)) {
                ranges.add(new Range(index, end));
            }
            index = haystack.indexOf(needle, index + 1);
        }
        return ranges;
    }

    private boolean overlaps(Range range, List<Range> consumed) {
        return consumed.stream().anyMatch(existing -> existing.start < range.end && range.start < existing.end);
    }

    private boolean isBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        return !Character.isLetterOrDigit(text.charAt(index));
    }

    private void extractBacktickedTerms(String text, Set<String> protectedTerms) {
        Matcher matcher = BACKTICK_PATTERN.matcher(text);
        while (matcher.find()) {
            protectedTerms.add(matcher.group(1));
        }
    }

    private void extractPatternTerms(String text, Set<String> protectedTerms, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            protectedTerms.add(matcher.group());
        }
    }

    private static BusinessException blankInput() {
        return new BusinessException(ErrorCode.LLM_001, "Embedding input is required");
    }

    private record TokenMatch(int start, int end, String text) {
        int length() {
            return end - start;
        }
    }

    private record AliasMatch(
            int order,
            String alias,
            String canonical,
            boolean exactMatch,
            List<Range> ranges
    ) {
        int length() {
            return alias.length();
        }
    }

    private record Range(int start, int end) {
    }
}
