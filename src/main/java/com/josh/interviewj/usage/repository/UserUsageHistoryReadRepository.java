package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.UsageEntryType;
import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistorySourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class UserUsageHistoryReadRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Page<HistoryRow> findHistory(
            Long userId,
            LocalDateTime from,
            LocalDateTime to,
            UsageHistoryCategory category,
            UsageHistorySourceType sourceType,
            Pageable pageable
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("category", category == null ? null : category.name())
                .addValue("sourceType", sourceType == null ? null : sourceType.name())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        String cteSql = historyCteSql();
        String filterSql = filterSql();
        Long total = jdbcTemplate.queryForObject(
                "WITH history_entries AS (" + cteSql + ") SELECT COUNT(*) FROM history_entries " + filterSql,
                params,
                Long.class
        );
        List<HistoryRow> content = jdbcTemplate.query(
                "WITH history_entries AS (" + cteSql + ") " +
                        """
                        SELECT *
                        FROM history_entries
                        """ + filterSql + """
                        ORDER BY occurred_at DESC, source_rank ASC, stable_sort_id DESC
                        LIMIT :limit OFFSET :offset
                        """,
                params,
                (rs, rowNum) -> new HistoryRow(
                        rs.getString("id"),
                        UsageEntryType.valueOf(rs.getString("entry_type")),
                        UsageHistoryCategory.valueOf(rs.getString("category")),
                        rs.getTimestamp("occurred_at").toLocalDateTime(),
                        rs.getLong("credits_delta_micros"),
                        UsageHistorySourceType.valueOf(rs.getString("source_type")),
                        longOrNull(rs.getObject("subscription_allocated_micros")),
                        longOrNull(rs.getObject("purchased_allocated_micros")),
                        rs.getString("usage_family"),
                        rs.getString("charge_bucket"),
                        rs.getString("resource_type"),
                        rs.getString("resource_external_id"),
                        rs.getString("operation_id"),
                        rs.getString("billing_event_type"),
                        rs.getString("billing_source_type"),
                        rs.getString("billing_source_id"),
                        rs.getString("billing_bucket_code"),
                        rs.getString("billing_metadata"),
                        rs.getString("rejection_reason_code"),
                        rs.getString("rejection_reason_message"),
                        rs.getString("rejection_metadata")
                ));
        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    private String historyCteSql() {
        return """
                SELECT
                    'usage-' || e.external_id::text AS id,
                    'USAGE' AS entry_type,
                    CASE cl.charge_bucket
                        WHEN 'RESUME_CREDITS' THEN 'RESUME'
                        WHEN 'KB_QUERY_CREDITS' THEN 'KB_QUERY'
                        WHEN 'KB_INGESTION_CREDITS' THEN 'KB_INGESTION'
                        WHEN 'INTERVIEW_CREDITS' THEN 'INTERVIEW'
                        ELSE 'REJECTED'
                    END AS category,
                    e.occurred_at AS occurred_at,
                    -COALESCE(cl.charged_credits_micros, 0) AS credits_delta_micros,
                    CASE
                        WHEN COALESCE((cl.metadata ->> 'subscriptionAllocatedMicros')::bigint, 0) > 0
                             AND COALESCE((cl.metadata ->> 'purchasedAllocatedMicros')::bigint, 0) > 0 THEN 'MIXED'
                        WHEN COALESCE((cl.metadata ->> 'subscriptionAllocatedMicros')::bigint, 0) > 0 THEN 'SUBSCRIPTION'
                        WHEN COALESCE((cl.metadata ->> 'purchasedAllocatedMicros')::bigint, 0) > 0 THEN 'PURCHASED'
                        ELSE 'UNKNOWN'
                    END AS source_type,
                    COALESCE((cl.metadata ->> 'subscriptionAllocatedMicros')::bigint, 0) AS subscription_allocated_micros,
                    COALESCE((cl.metadata ->> 'purchasedAllocatedMicros')::bigint, 0) AS purchased_allocated_micros,
                    e.usage_family AS usage_family,
                    cl.charge_bucket::text AS charge_bucket,
                    e.resource_type AS resource_type,
                    e.resource_external_id AS resource_external_id,
                    e.operation_id AS operation_id,
                    NULL::text AS billing_event_type,
                    NULL::text AS billing_source_type,
                    NULL::text AS billing_source_id,
                    NULL::text AS billing_bucket_code,
                    NULL::text AS billing_metadata,
                    NULL::text AS rejection_reason_code,
                    NULL::text AS rejection_reason_message,
                    NULL::text AS rejection_metadata,
                    1 AS source_rank,
                    e.id AS stable_sort_id
                FROM llm_usage_event e
                JOIN llm_usage_credit_ledger cl ON cl.usage_event_id = e.id
                WHERE e.user_id = :userId
                  AND cl.charge_status = 'CHARGEABLE'
                  AND cl.charge_bucket IS NOT NULL
                  AND COALESCE(cl.charged_credits_micros, 0) > 0

                UNION ALL

                SELECT
                    'rejection-' || r.external_id::text AS id,
                    'USAGE_REJECTED' AS entry_type,
                    'REJECTED' AS category,
                    r.occurred_at AS occurred_at,
                    0 AS credits_delta_micros,
                    'SYSTEM' AS source_type,
                    NULL::bigint AS subscription_allocated_micros,
                    NULL::bigint AS purchased_allocated_micros,
                    r.usage_family AS usage_family,
                    r.charge_bucket AS charge_bucket,
                    r.resource_type AS resource_type,
                    r.resource_external_id AS resource_external_id,
                    r.operation_id AS operation_id,
                    NULL::text AS billing_event_type,
                    NULL::text AS billing_source_type,
                    NULL::text AS billing_source_id,
                    NULL::text AS billing_bucket_code,
                    NULL::text AS billing_metadata,
                    r.reason_code AS rejection_reason_code,
                    r.reason_message AS rejection_reason_message,
                    CAST(r.metadata AS text) AS rejection_metadata,
                    2 AS source_rank,
                    r.id AS stable_sort_id
                FROM usage_rejection_records r
                WHERE r.user_id = :userId

                UNION ALL

                SELECT
                    'billing-' || b.external_id::text AS id,
                    CASE
                        WHEN b.event_type IN ('SUBSCRIPTION_QUOTA_GRANTED', 'CREDIT_PURCHASE_GRANTED', 'ACTIVATION_CODE_CREDIT_GRANTED')
                            THEN 'GRANT'
                        ELSE 'ADJUSTMENT'
                    END AS entry_type,
                    CASE
                        WHEN b.event_type IN ('SUBSCRIPTION_QUOTA_GRANTED', 'CREDIT_PURCHASE_GRANTED', 'ACTIVATION_CODE_CREDIT_GRANTED')
                            THEN 'GRANT'
                        ELSE 'ADJUSTMENT'
                    END AS category,
                    b.occurred_at AS occurred_at,
                    CASE
                        WHEN b.event_type = 'SUBSCRIPTION_QUOTA_GRANTED' THEN COALESCE(sqg.granted_amount_micros, 0)
                        ELSE b.delta_amount_micros
                    END AS credits_delta_micros,
                    CASE
                        WHEN b.event_type = 'SUBSCRIPTION_QUOTA_GRANTED' THEN 'SUBSCRIPTION'
                        ELSE 'PURCHASED'
                    END AS source_type,
                    NULL::bigint AS subscription_allocated_micros,
                    NULL::bigint AS purchased_allocated_micros,
                    NULL::text AS usage_family,
                    NULL::text AS charge_bucket,
                    NULL::text AS resource_type,
                    NULL::text AS resource_external_id,
                    NULL::text AS operation_id,
                    b.event_type::text AS billing_event_type,
                    b.source_type AS billing_source_type,
                    b.source_id AS billing_source_id,
                    b.bucket_code AS billing_bucket_code,
                    CAST(b.metadata AS text) AS billing_metadata,
                    NULL::text AS rejection_reason_code,
                    NULL::text AS rejection_reason_message,
                    NULL::text AS rejection_metadata,
                    3 AS source_rank,
                    b.id AS stable_sort_id
                FROM billing_event b
                LEFT JOIN (
                    SELECT
                        source_billing_event_id,
                        SUM(granted_amount_micros) AS granted_amount_micros
                    FROM subscription_quota_grant
                    GROUP BY source_billing_event_id
                ) sqg ON sqg.source_billing_event_id = b.id
                WHERE b.user_id = :userId
                  AND (
                    b.event_type IN ('SUBSCRIPTION_QUOTA_GRANTED', 'CREDIT_PURCHASE_GRANTED', 'ACTIVATION_CODE_CREDIT_GRANTED')
                    OR b.event_type IN ('PAYMENT_REFUNDED', 'PAYMENT_CHARGEBACK')
                    OR (
                        b.event_type = 'MANUAL_ADJUSTMENT'
                        AND b.bucket_code IS NULL
                        AND b.delta_amount_micros <> 0
                    )
                  )
                """;
    }

    private String filterSql() {
        return """
                WHERE (CAST(:from AS timestamp) IS NULL OR occurred_at >= :from)
                  AND (CAST(:to AS timestamp) IS NULL OR occurred_at < :to)
                  AND (CAST(:category AS varchar) IS NULL OR category = :category)
                  AND (CAST(:sourceType AS varchar) IS NULL OR source_type = :sourceType)
                """;
    }

    private Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record HistoryRow(
            String id,
            UsageEntryType entryType,
            UsageHistoryCategory category,
            LocalDateTime occurredAt,
            Long creditsDeltaMicros,
            UsageHistorySourceType sourceType,
            Long subscriptionAllocatedMicros,
            Long purchasedAllocatedMicros,
            String usageFamily,
            String chargeBucket,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String billingEventType,
            String billingSourceType,
            String billingSourceId,
            String billingBucketCode,
            String billingMetadata,
            String rejectionReasonCode,
            String rejectionReasonMessage,
            String rejectionMetadata
    ) {
    }
}
