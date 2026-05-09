package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UsageFailureCompensationServiceTest {

    @Test
    void recordFailure_UsesRequiresNewAndDelegatesToSettlementService() throws Exception {
        TransactionalUsageSettlementService settlementService = mock(TransactionalUsageSettlementService.class);
        UsageFailureCompensationService service = new UsageFailureCompensationService(settlementService);
        UsageOperationContext context = failureContext(UsageBusinessOutcome.FAILED_NON_CHARGEABLE);

        service.recordFailure(context);

        Method method = UsageFailureCompensationService.class.getDeclaredMethod("recordFailure", UsageOperationContext.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        verify(settlementService).record(context);
    }

    @Test
    void recordFailure_WhenOutcomeIsSuccess_RejectsInvalidInput() {
        TransactionalUsageSettlementService settlementService = mock(TransactionalUsageSettlementService.class);
        UsageFailureCompensationService service = new UsageFailureCompensationService(settlementService);

        assertThatThrownBy(() -> service.recordFailure(failureContext(UsageBusinessOutcome.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-chargeable");
    }

    private UsageOperationContext failureContext(UsageBusinessOutcome outcome) {
        return new UsageOperationContext(
                "rag",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 80L, 20L, 100L, 0L),
                "KNOWLEDGE_BASE_QUERY",
                "kb-ext-1",
                "op-2",
                202L,
                outcome,
                "upstream failed"
        );
    }
}
