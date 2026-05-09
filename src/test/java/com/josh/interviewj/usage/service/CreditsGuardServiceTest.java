package com.josh.interviewj.usage.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.model.UsageRejectionReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditsGuardServiceTest {

    @Mock
    private CreditsBalanceSnapshotService creditsBalanceSnapshotService;

    @Mock
    private UsageRejectionRecordingService usageRejectionRecordingService;

    private CreditsGuardService service;

    @BeforeEach
    void setUp() {
        service = new CreditsGuardService(
                Clock.fixed(Instant.parse("2026-04-09T09:00:00Z"), ZoneOffset.UTC),
                creditsBalanceSnapshotService,
                usageRejectionRecordingService
        );
    }

    @Test
    void requirePositiveSpendableCredits_WhenSpendableCreditsIsZero_RejectsAndRecords() {
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                -20_000L, 0L, 0L, 0L, 0L, -20_000L, 0L, true, true, null, List.of()
        ));

        assertThatThrownBy(() -> service.requirePositiveSpendableCredits(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Insufficient billing balance")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_BILLING_001);

        verify(usageRejectionRecordingService).recordPreflightRejected(
                eq(101L),
                eq("KB_QUERY_CREDITS"),
                eq("CHAT"),
                eq("KNOWLEDGE_BASE_QUERY"),
                eq("kb-1"),
                eq("op-1"),
                eq(null),
                eq(UsageRejectionReasonCode.INSUFFICIENT_CREDITS),
                eq("Insufficient billing balance"),
                any(),
                any()
        );
    }

    @Test
    void requirePositiveSpendableCredits_WhenSpendableCreditsPositive_PassesWithoutRecording() {
        CreditsBalanceSnapshot snapshot = new CreditsBalanceSnapshot(
                100L, 100L, 100L, 0L, 0L, 100L, 100L, true, false, null, List.of()
        );
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(snapshot);

        CreditsBalanceSnapshot result = service.requirePositiveSpendableCredits(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1"
        );

        assertThat(result).isSameAs(snapshot);
        verify(usageRejectionRecordingService, never()).recordPreflightRejected(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void requirePositiveSpendableCredits_NewOverload_PassesBusinessOperationIdToRejectionRecorder() {
        when(creditsBalanceSnapshotService.getSnapshot(101L)).thenReturn(new CreditsBalanceSnapshot(
                -20_000L, 0L, 0L, 0L, 0L, -20_000L, 0L, true, true, null, List.of()
        ));

        assertThatThrownBy(() -> service.requirePositiveSpendableCredits(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1",
                "biz-1"
        )).isInstanceOf(BusinessException.class);

        verify(usageRejectionRecordingService).recordPreflightRejected(
                eq(101L),
                eq("KB_QUERY_CREDITS"),
                eq("CHAT"),
                eq("KNOWLEDGE_BASE_QUERY"),
                eq("kb-1"),
                eq("op-1"),
                eq("biz-1"),
                eq(UsageRejectionReasonCode.INSUFFICIENT_CREDITS),
                eq("Insufficient billing balance"),
                any(),
                any()
        );
    }
}
