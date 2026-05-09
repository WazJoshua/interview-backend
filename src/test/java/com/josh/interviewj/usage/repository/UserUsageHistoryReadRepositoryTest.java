package com.josh.interviewj.usage.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserUsageHistoryReadRepositoryTest {

    @Test
    void historyCteSql_FiltersOutZeroCreditUsageRows() throws Exception {
        String sql = historyCteSql();

        assertThat(sql).contains("COALESCE(cl.charged_credits_micros, 0) > 0");
    }

    @Test
    void historyCteSql_UsesQuotaGrantAmountForSubscriptionGrantCredits() throws Exception {
        String sql = historyCteSql();

        assertThat(sql).contains("FROM subscription_quota_grant");
        assertThat(sql).contains("sqg.source_billing_event_id = b.id");
        assertThat(sql).contains("WHEN b.event_type = 'SUBSCRIPTION_QUOTA_GRANTED' THEN COALESCE(sqg.granted_amount_micros, 0)");
    }

    @Test
    void filterSql_CastsNullableParametersToConcreteTypes() throws Exception {
        String sql = filterSql();

        assertThat(sql).contains("CAST(:from AS timestamp) IS NULL OR occurred_at >= :from");
        assertThat(sql).contains("CAST(:to AS timestamp) IS NULL OR occurred_at < :to");
        assertThat(sql).contains("CAST(:category AS varchar) IS NULL OR category = :category");
        assertThat(sql).contains("CAST(:sourceType AS varchar) IS NULL OR source_type = :sourceType");
    }

    private String historyCteSql() throws Exception {
        UserUsageHistoryReadRepository repository = repository();
        Method method = UserUsageHistoryReadRepository.class.getDeclaredMethod("historyCteSql");
        method.setAccessible(true);
        return (String) method.invoke(repository);
    }

    private String filterSql() throws Exception {
        UserUsageHistoryReadRepository repository = repository();
        Method method = UserUsageHistoryReadRepository.class.getDeclaredMethod("filterSql");
        method.setAccessible(true);
        return (String) method.invoke(repository);
    }

    private UserUsageHistoryReadRepository repository() {
        UserUsageHistoryReadRepository repository = new UserUsageHistoryReadRepository(mock(NamedParameterJdbcTemplate.class));
        return repository;
    }
}
