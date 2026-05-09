package com.josh.interviewj.usage.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.usage.dto.request.UserUsageHistoryQuery;
import com.josh.interviewj.usage.dto.response.UserUsageHistoryItemResponse;
import com.josh.interviewj.usage.model.UsageEntryType;
import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistorySourceType;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import com.josh.interviewj.usage.repository.UserUsageHistoryReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserUsageHistoryQueryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserUsageHistoryReadRepository userUsageHistoryReadRepository;

    @Mock
    private CreditsBalanceSnapshotService creditsBalanceSnapshotService;

    private UserUsageHistoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new UserUsageHistoryQueryService(
                Clock.fixed(Instant.parse("2026-04-09T09:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build(),
                userRepository,
                userUsageHistoryReadRepository,
                creditsBalanceSnapshotService,
                new CreditFormattingService()
        );
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));
    }

    @Test
    void getCurrentUserHistory_WhenNoRecords_ReturnsEmptyPage() {
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false, null, List.of()
        ));
        when(userUsageHistoryReadRepository.findHistory(eq(101L), any(), any(), eq(null), eq(null), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        Page<UserUsageHistoryItemResponse> page = service.getCurrentUserHistory("josh", new UserUsageHistoryQuery());

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void getCurrentUserHistory_DefaultWindowUsesSubscriptionPeriodWhenActive() {
        UserUsageHistoryQuery query = new UserUsageHistoryQuery();
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                0L,
                0L,
                0L,
                0L,
                300_000L,
                300_000L,
                300_000L,
                true,
                false,
                com.josh.interviewj.billing.model.SubscriptionContract.builder()
                        .id(21L)
                        .currentPeriodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .currentPeriodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                        .build(),
                List.of()
        ));
        when(userUsageHistoryReadRepository.findHistory(eq(101L), any(), any(), eq(null), eq(null), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        service.getCurrentUserHistory("josh", query);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userUsageHistoryReadRepository).findHistory(eq(101L), fromCaptor.capture(), toCaptor.capture(), eq(null), eq(null), any());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
    }

    @Test
    void getCurrentUserHistory_DefaultWindowFallsBackToLast30DaysWithoutSubscription() {
        UserUsageHistoryQuery query = new UserUsageHistoryQuery();
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false, null, List.of()
        ));
        when(userUsageHistoryReadRepository.findHistory(eq(101L), any(), any(), eq(null), eq(null), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        service.getCurrentUserHistory("josh", query);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userUsageHistoryReadRepository).findHistory(eq(101L), fromCaptor.capture(), toCaptor.capture(), eq(null), eq(null), any());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 3, 10, 9, 0));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 4, 9, 9, 0));
    }

    @Test
    void getCurrentUserHistory_MapsUsageGrantAdjustmentAndRejectionEntries() {
        UserUsageHistoryQuery query = new UserUsageHistoryQuery();
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false, null, List.of()
        ));
        when(userUsageHistoryReadRepository.findHistory(eq(101L), any(), any(), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new UserUsageHistoryReadRepository.HistoryRow(
                                "usage-1",
                                UsageEntryType.USAGE,
                                UsageHistoryCategory.KB_QUERY,
                                LocalDateTime.of(2026, 4, 9, 8, 30),
                                -2_500L,
                                UsageHistorySourceType.MIXED,
                                2_000L,
                                500L,
                                "EMBEDDING",
                                "KB_QUERY_CREDITS",
                                "KNOWLEDGE_BASE_QUERY",
                                "kb_123",
                                "query_456",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ),
                        new UserUsageHistoryReadRepository.HistoryRow(
                                "billing-1",
                                UsageEntryType.ADJUSTMENT,
                                UsageHistoryCategory.ADJUSTMENT,
                                LocalDateTime.of(2026, 4, 8, 10, 0),
                                -10_000L,
                                UsageHistorySourceType.PURCHASED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "PAYMENT_REFUNDED",
                                "PAYMENT_EVENT",
                                "trade_123",
                                null,
                                "{\"refund\":true}",
                                null,
                                null,
                                null
                        ),
                        new UserUsageHistoryReadRepository.HistoryRow(
                                "rejection-1",
                                UsageEntryType.USAGE_REJECTED,
                                UsageHistoryCategory.REJECTED,
                                LocalDateTime.of(2026, 4, 8, 10, 30),
                                0L,
                                UsageHistorySourceType.SYSTEM,
                                null,
                                null,
                                "CHAT",
                                "INTERVIEW_CREDITS",
                                "INTERVIEW_SESSION",
                                "session_123",
                                "report_456",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "INSUFFICIENT_CREDITS",
                                "Insufficient billing balance",
                                "{\"code\":\"USER_BILLING_001\"}"
                        )
                ), PageRequest.of(0, 20), 3));

        Page<UserUsageHistoryItemResponse> page = service.getCurrentUserHistory("josh", query);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent().get(0).getSourceBreakdown().getSubscriptionAllocatedMicros()).isEqualTo(2_000L);
        assertThat(page.getContent().get(0).getUsage().getOperationId()).isEqualTo("query_456");
        assertThat(page.getContent().get(1).getAdjustment().getEventType()).isEqualTo("PAYMENT_REFUNDED");
        assertThat(page.getContent().get(2).getRejection().getReasonCode()).isEqualTo("INSUFFICIENT_CREDITS");
    }

    @Test
    void getCurrentUserHistory_UsesExplicitWindowTypes() {
        UserUsageHistoryQuery query = new UserUsageHistoryQuery();
        query.setWindowType(UsageHistoryWindowType.LAST_90_DAYS);
        when(userUsageHistoryReadRepository.findHistory(eq(101L), any(), any(), eq(null), eq(null), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        service.getCurrentUserHistory("josh", query);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userUsageHistoryReadRepository).findHistory(eq(101L), fromCaptor.capture(), toCaptor.capture(), eq(null), eq(null), any());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 1, 9, 9, 0));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 4, 9, 9, 0));
    }
}
