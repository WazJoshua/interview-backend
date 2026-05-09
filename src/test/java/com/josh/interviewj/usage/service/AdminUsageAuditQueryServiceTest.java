package com.josh.interviewj.usage.service;

import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUsageAuditQueryServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private AdminUsageAuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminUsageAuditQueryService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build(),
                jdbcTemplate,
                new CreditFormattingService()
        );
    }

    @Test
    void getUsageEvents_WhenOnlyToProvided_DoesNotAddLowerBoundPredicate() {
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        AdminUsageEventsQuery query = new AdminUsageEventsQuery();
        query.setTo(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        service.getUsageEvents(query);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Long.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("e.occurred_at >= :from");
        assertThat(sqlCaptor.getValue()).contains("e.occurred_at < :to");
        assertThat(paramsCaptor.getValue().getValues()).doesNotContainKey("from");
        assertThat(paramsCaptor.getValue().getValues()).containsKey("to");
    }

    @Test
    void getUsageEvents_PrefersCanonicalScopeFromForeignKeys() {
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        service.getUsageEvents(new AdminUsageEventsQuery());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(sqlCaptor.getValue()).contains("LEFT JOIN llm_provider provider_ref");
        assertThat(sqlCaptor.getValue()).contains("LEFT JOIN llm_model_catalog model_ref");
        assertThat(sqlCaptor.getValue()).contains("COALESCE(model_provider.provider_key, provider_ref.provider_key, e.provider) AS provider");
        assertThat(sqlCaptor.getValue()).contains("COALESCE(model_ref.model_code, e.model_code) AS model_code");
    }
}
