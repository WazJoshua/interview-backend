package com.josh.interviewj.usage.service;

import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.model.UsageRejectionReasonCode;
import com.josh.interviewj.usage.model.UsageRejectionRecord;
import com.josh.interviewj.usage.repository.UsageRejectionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageRejectionRecordingServiceTest {

    @Mock
    private UsageRejectionRecordRepository usageRejectionRecordRepository;

    private UsageRejectionRecordingService service;

    @BeforeEach
    void setUp() {
        service = new UsageRejectionRecordingService(
                JsonMapper.builder().build(),
                usageRejectionRecordRepository
        );
    }

    @Test
    void recordPreflightRejected_PersistsBasicFields() {
        when(usageRejectionRecordRepository.findByDedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS"))
                .thenReturn(Optional.empty());
        when(usageRejectionRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UsageRejectionRecord record = service.recordPreflightRejected(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1",
                "biz-1",
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                Map.of("errorCode", ErrorCode.USER_BILLING_001),
                LocalDateTime.of(2026, 4, 9, 10, 0)
        );

        assertThat(record.getDedupeKey()).isEqualTo("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS");
        assertThat(record.getResourceExternalId()).isEqualTo("kb-1");
        assertThat(record.getBusinessOperationId()).isEqualTo("biz-1");
        assertThat(record.getReasonCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(record.getMetadata()).contains("USER_BILLING_001");
    }

    @Test
    void recordDebitRejected_PersistsContextScope() {
        when(usageRejectionRecordRepository.findByDedupeKey("101|op-1|RESUME_CREDITS|INSUFFICIENT_CREDITS"))
                .thenReturn(Optional.empty());
        when(usageRejectionRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UsageRejectionRecord record = service.recordDebitRejected(
                new UsageOperationContext(
                        "analysis",
                        "default",
                        "qwen-plus",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 10L, 20L, 0L),
                        "RESUME_ANALYSIS_REPORT",
                        "resume-1",
                        "op-1",
                        "biz-1",
                        101L,
                        UsageBusinessOutcome.SUCCESS,
                        null,
                        "EXECUTED",
                        null,
                        null,
                        Map.of()
                ),
                "RESUME_CREDITS",
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                LocalDateTime.of(2026, 4, 9, 10, 5)
        );

        assertThat(record.getChargeBucket()).isEqualTo("RESUME_CREDITS");
        assertThat(record.getUsageFamily()).isEqualTo("CHAT");
        assertThat(record.getOperationId()).isEqualTo("op-1");
        assertThat(record.getBusinessOperationId()).isEqualTo("biz-1");
        assertThat(record.getDedupeKey()).isEqualTo("101|op-1|RESUME_CREDITS|INSUFFICIENT_CREDITS");
    }

    @Test
    void recordPreflightRejected_WhenDuplicateExists_DoesNotCreateSecondRecord() {
        UsageRejectionRecord existing = UsageRejectionRecord.builder()
                .dedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS")
                .build();
        when(usageRejectionRecordRepository.findByDedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS"))
                .thenReturn(Optional.of(existing));

        UsageRejectionRecord record = service.recordPreflightRejected(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1",
                "biz-1",
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                Map.of(),
                LocalDateTime.of(2026, 4, 9, 10, 0)
        );

        assertThat(record).isSameAs(existing);
        verify(usageRejectionRecordRepository, never()).save(any());
    }

    @Test
    void recordPreflightRejected_UsesCreateOrGetWhenRaceCreatesDuplicate() {
        UsageRejectionRecord existing = UsageRejectionRecord.builder()
                .dedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS")
                .build();
        when(usageRejectionRecordRepository.findByDedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(usageRejectionRecordRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        UsageRejectionRecord record = service.recordPreflightRejected(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1",
                "biz-1",
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                Map.of(),
                LocalDateTime.of(2026, 4, 9, 10, 0)
        );

        assertThat(record).isSameAs(existing);
    }

    @Test
    void recordMethods_AreRequiresNewTransactions() throws Exception {
        Transactional preflight = UsageRejectionRecordingService.class
                .getMethod(
                        "recordPreflightRejected",
                        Long.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        UsageRejectionReasonCode.class,
                        String.class,
                        Map.class,
                        LocalDateTime.class
                )
                .getAnnotation(Transactional.class);
        Transactional debit = UsageRejectionRecordingService.class
                .getMethod(
                        "recordDebitRejected",
                        UsageOperationContext.class,
                        String.class,
                        UsageRejectionReasonCode.class,
                        String.class,
                        LocalDateTime.class
                )
                .getAnnotation(Transactional.class);

        assertThat(preflight.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(debit.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void recordPreflightRejected_WithoutBusinessOperationId_FallsBackToOperationIdDedupeScope() {
        when(usageRejectionRecordRepository.findByDedupeKey("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS"))
                .thenReturn(Optional.empty());
        when(usageRejectionRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UsageRejectionRecord record = service.recordPreflightRejected(
                101L,
                "KB_QUERY_CREDITS",
                "CHAT",
                "KNOWLEDGE_BASE_QUERY",
                "kb-1",
                "op-1",
                UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                "Insufficient billing balance",
                Map.of(),
                LocalDateTime.of(2026, 4, 9, 10, 0)
        );

        assertThat(record.getDedupeKey()).isEqualTo("101|op-1|KB_QUERY_CREDITS|INSUFFICIENT_CREDITS");
    }
}
