package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiteralSignalDetectorTest {

    @Test
    void detect_ConfigPathAndCodeSymbol_ReturnsTypedFlags() throws Exception {
        NormalizedQuery normalizedQuery = new NormalizedQuery(
                "spring.profiles.active 和 KnowledgeBaseQueryService 怎么处理 AUTH_001 --tests",
                "spring.profiles.active 和 KnowledgeBaseQueryService 怎么处理 AUTH_001 --tests",
                List.of("spring.profiles.active", "KnowledgeBaseQueryService", "AUTH_001", "--tests"),
                List.of("spring.profiles.active", "KnowledgeBaseQueryService", "AUTH_001", "--tests"),
                List.of(),
                new QueryProfile(false, false, true, false, false)
        );

        Object literalSignals = detect(normalizedQuery);

        assertThat(booleanValue(literalSignals, "hasConfigPath")).isTrue();
        assertThat(booleanValue(literalSignals, "hasCodeSymbol")).isTrue();
        assertThat(booleanValue(literalSignals, "hasErrorCode")).isTrue();
        assertThat(booleanValue(literalSignals, "hasCliOption")).isTrue();
        assertThat(intValue(literalSignals, "matchedTokenCount")).isGreaterThan(1);
        assertThat(listValue(literalSignals, "exactBoostTerms"))
                .contains("spring.profiles.active", "KnowledgeBaseQueryService", "AUTH_001", "--tests");
    }

    private Object detect(NormalizedQuery normalizedQuery) throws Exception {
        Class<?> detectorClass = Class.forName("com.josh.interviewj.ragqa.service.LiteralSignalDetector");
        Object detector = detectorClass.getConstructor().newInstance();
        Method detect = detectorClass.getMethod("detect", NormalizedQuery.class);
        return detect.invoke(detector, normalizedQuery);
    }

    private boolean booleanValue(Object target, String accessor) throws Exception {
        return (boolean) target.getClass().getMethod(accessor).invoke(target);
    }

    private int intValue(Object target, String accessor) throws Exception {
        return (int) target.getClass().getMethod(accessor).invoke(target);
    }

    @SuppressWarnings("unchecked")
    private List<String> listValue(Object target, String accessor) throws Exception {
        return (List<String>) target.getClass().getMethod(accessor).invoke(target);
    }
}
