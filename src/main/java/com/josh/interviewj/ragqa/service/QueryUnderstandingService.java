package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.LiteralSignalProfile;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import com.josh.interviewj.ragqa.model.RewriteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QueryUnderstandingService {

    private final QueryUnderstandingProperties properties;
    private final QueryNormalizationService queryNormalizationService;
    private final QueryProfileDetector queryProfileDetector;
    private final QueryRewriteService queryRewriteService;
    private final LiteralSignalDetector literalSignalDetector;

    @Autowired
    public QueryUnderstandingService(
            QueryUnderstandingProperties properties,
            QueryNormalizationService queryNormalizationService,
            QueryProfileDetector queryProfileDetector,
            QueryRewriteService queryRewriteService,
            LiteralSignalDetector literalSignalDetector
    ) {
        this.properties = properties;
        this.queryNormalizationService = queryNormalizationService;
        this.queryProfileDetector = queryProfileDetector;
        this.queryRewriteService = queryRewriteService;
        this.literalSignalDetector = literalSignalDetector;
    }

    public QueryUnderstandingService(
            QueryUnderstandingProperties properties,
            QueryNormalizationService queryNormalizationService,
            QueryProfileDetector queryProfileDetector,
            QueryRewriteService queryRewriteService
    ) {
        this(
                properties,
                queryNormalizationService,
                queryProfileDetector,
                queryRewriteService,
                new LiteralSignalDetector()
        );
    }

    public NormalizedQuery understand(String rawQuestion) {
        if (!properties.enabled()) {
            return new NormalizedQuery(
                    rawQuestion,
                    rawQuestion,
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    QueryProfile.none(),
                    LiteralSignalProfile.none()
            );
        }

        NormalizedQuery normalizedQuery = queryNormalizationService.normalize(rawQuestion);
        QueryProfile profile = queryProfileDetector.detect(normalizedQuery);
        LiteralSignalProfile literalSignals = literalSignalDetector.detect(normalizedQuery);
        return new NormalizedQuery(
                normalizedQuery.rawText(),
                normalizedQuery.normalizedText(),
                normalizedQuery.preservedTokens(),
                normalizedQuery.protectedTerms(),
                normalizedQuery.aliasExpansions(),
                profile,
                literalSignals
        );
    }

    public RewriteResult rewrite(NormalizedQuery normalizedQuery) {
        if (!properties.enabled() || !properties.isRewriteEnabled()) {
            return RewriteResult.notAttempted(normalizedQuery.normalizedText());
        }
        return queryRewriteService.rewrite(normalizedQuery);
    }

    QueryRewriteService queryRewriteService() {
        return queryRewriteService;
    }

    public String truncateForLog(String text) {
        if (text == null || properties.getLogQueryMaxChars() <= 0 || text.length() <= properties.getLogQueryMaxChars()) {
            return text;
        }
        return text.substring(0, properties.getLogQueryMaxChars()) + "...";
    }
}
