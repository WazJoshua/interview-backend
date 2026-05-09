package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.QueryProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryProfileDetectorTest {

    private final QueryProfileDetector detector = new QueryProfileDetector();

    @Test
    void detect_ShortStructuredTokenQuery_DoesNotOverTagConversational() {
        QueryProfile profile = detector.detect(new NormalizedQuery(
                "怎么配 AUTH_006",
                "怎么配 AUTH_006",
                List.of("AUTH_006"),
                List.of("AUTH_006"),
                List.of(),
                QueryProfile.none()
        ));

        assertThat(profile.shortQuery()).isTrue();
        assertThat(profile.hasStructuredTokens()).isTrue();
        assertThat(profile.likelyConversational()).isFalse();
    }

    @Test
    void detect_LongConversationalQuery_SetsLongAndConversationalFlags() {
        String query = "请问现在这个知识库查询链路如果我想在不改 public API 的前提下，把 rewrite 和 dual branch retrieval 做得更稳一些，"
                + "有没有一条比较保险、便于回退、还能观察日志的实施路径？"
                + "另外如果还要保证 timeout、fallback、离线评估、rollout gate 和 underfill 日志都能保留，"
                + "是不是应该把 query understanding 先拆成 normalization、rewrite、retrieval planning 三层？";
        QueryProfile profile = detector.detect(new NormalizedQuery(
                query,
                query,
                List.of(),
                List.of(),
                List.of(),
                QueryProfile.none()
        ));

        assertThat(profile.longQuery()).isTrue();
        assertThat(profile.likelyConversational()).isTrue();
    }

    @Test
    void detect_AliasExpansionOrAcronym_SetsTerminologyDrift() {
        QueryProfile aliasProfile = detector.detect(new NormalizedQuery(
                "aof 是什么",
                "aof 是什么 append only file",
                List.of("aof"),
                List.of("aof"),
                List.of("append only file"),
                QueryProfile.none()
        ));
        QueryProfile acronymProfile = detector.detect(new NormalizedQuery(
                "AOF 持久化",
                "AOF 持久化",
                List.of("AOF"),
                List.of("AOF"),
                List.of(),
                QueryProfile.none()
        ));

        assertThat(aliasProfile.likelyTerminologyDrift()).isTrue();
        assertThat(acronymProfile.likelyTerminologyDrift()).isTrue();
    }
}
