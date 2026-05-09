package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.AiOperationPlan;
import com.josh.interviewj.llm.gateway.dto.AiOperationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.executor.AiCapabilityExecutor;
import com.josh.interviewj.llm.gateway.executor.AiCapabilityExecutorRegistry;
import com.josh.interviewj.llm.gateway.support.AiGatewayUsageBridge;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.service.CreditsGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiOperationGatewayTest {

    @Mock
    private CreditsGuardService creditsGuardService;

    @Mock
    private AiCapabilityExecutorRegistry executorRegistry;

    @Mock
    private AiCapabilityExecutor executor;

    @Mock
    private AiGatewayUsageBridge usageBridge;

    private DefaultAiOperationGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DefaultAiOperationGateway(creditsGuardService, executorRegistry, usageBridge);
    }

    @Test
    void executeInvocation_Success_DoesNotRecordUsageBeforeOutcomeSubmission() {
        BusinessOperationContext operationContext = operationContext();
        AiInvocationContext invocationContext = invocationContext("inv-1");
        AiInvocationInput input = AiInvocationInput.chat("system", "user", null);
        AiInvocationResult result = AiInvocationResult.fromChat(chatResponse());
        when(executorRegistry.get(AiInvocationKind.CHAT)).thenReturn(executor);
        when(executor.execute(invocationContext, input)).thenReturn(result);

        AiInvocationResult actual = gateway.executeInvocation(operationContext, invocationContext, input);

        assertThat(actual).isSameAs(result);
        verify(usageBridge, never()).recordInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitInvocationOutcome_Success_DelegatesToUsageBridge() {
        AiInvocationResult result = AiInvocationResult.fromChat(chatResponse());

        gateway.submitInvocationOutcome(
                operationContext(),
                invocationContext("inv-1"),
                result,
                ExecutionDisposition.EXECUTED,
                InvocationUsageOutcome.SUCCESS,
                null
        );

        verify(usageBridge).recordInvocationOutcome(
                any(),
                any(),
                eq(result),
                eq(ExecutionDisposition.EXECUTED),
                eq(InvocationUsageOutcome.SUCCESS),
                eq(null)
        );
    }

    @Test
    void prepareOperation_DeduplicatesRepeatedBuckets() {
        BusinessOperationContext operationContext = new BusinessOperationContext(
                "biz-1",
                101L,
                "RESUME_ANALYSIS_REPORT",
                "report-1",
                "analysis",
                List.of("RESUME_CREDITS", "RESUME_CREDITS"),
                Map.of()
        );

        gateway.prepareOperation(operationContext);

        verify(creditsGuardService).requirePositiveSpendableCredits(
                101L,
                "RESUME_CREDITS",
                "CHAT",
                "RESUME_ANALYSIS_REPORT",
                "report-1",
                "preflight:biz-1",
                "biz-1"
        );
    }

    @Test
    void prepareOperation_UsesEmbeddingUsageFamilyForEmbeddingScenario() {
        BusinessOperationContext operationContext = new BusinessOperationContext(
                "biz-2",
                101L,
                "KB_DOCUMENT",
                "doc-1",
                "kb_document_embedding",
                List.of("KB_INGESTION_CREDITS"),
                Map.of()
        );

        gateway.prepareOperation(operationContext);

        verify(creditsGuardService).requirePositiveSpendableCredits(
                101L,
                "KB_INGESTION_CREDITS",
                "EMBEDDING",
                "KB_DOCUMENT",
                "doc-1",
                "preflight:biz-2",
                "biz-2"
        );
    }

    @Test
    void runOperation_ReturnsInvocationResultsAndAggregateOutcome() {
        BusinessOperationContext operationContext = operationContext();
        AiInvocationContext firstInvocation = invocationContext("inv-1");
        AiInvocationContext secondInvocation = invocationContext("inv-2");
        AiInvocationInput input = AiInvocationInput.chat("system", "user", null);
        AiInvocationResult result = AiInvocationResult.fromChat(chatResponse());

        when(executorRegistry.get(AiInvocationKind.CHAT)).thenReturn(executor);
        when(executor.execute(any(), eq(input))).thenReturn(result);

        AiOperationResult operationResult = gateway.runOperation(
                operationContext,
                new AiOperationPlan(List.of(
                        new AiOperationPlan.Step(
                                firstInvocation,
                                input,
                                ExecutionDisposition.EXECUTED,
                                InvocationUsageOutcome.SUCCESS,
                                null
                        ),
                        new AiOperationPlan.Step(
                                secondInvocation,
                                input,
                                ExecutionDisposition.EXECUTED,
                                InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                                "failed"
                        )
                ))
        );

        assertThat(operationResult.businessOperationId()).isEqualTo("biz-1");
        assertThat(operationResult.invocationResults()).hasSize(2);
        assertThat(operationResult.aggregateOutcome()).isEqualTo(InvocationUsageOutcome.FAILED_NON_CHARGEABLE);
    }

    private BusinessOperationContext operationContext() {
        return new BusinessOperationContext(
                "biz-1",
                101L,
                "RESUME_ANALYSIS_REPORT",
                "report-1",
                "analysis",
                List.of("RESUME_CREDITS"),
                Map.of()
        );
    }

    private AiInvocationContext invocationContext(String invocationId) {
        return new AiInvocationContext(
                invocationId,
                "analysis",
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Map.of()
        );
    }

    private LlmResponse chatResponse() {
        return new LlmResponse(
                "{\"ok\":true}",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        );
    }
}
