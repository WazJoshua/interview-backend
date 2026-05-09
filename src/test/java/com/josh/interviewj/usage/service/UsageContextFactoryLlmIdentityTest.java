package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmModelCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageContextFactoryLlmIdentityTest {

    @Test
    void successFromLlm_AttachesResolvedProviderAndModelIds() {
        LlmModelCatalogRepository repository = mock(LlmModelCatalogRepository.class);
        when(repository.findByProviderAndModelCodeAndUsageFamilyWithProvider("db-provider", "db-model", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder()
                        .id(21L)
                        .provider("db-provider")
                        .modelCode("db-model")
                        .usageFamily(UsageFamily.CHAT)
                        .providerRef(LlmProvider.builder().id(11L).providerKey("db-provider").build())
                        .build()));

        UsageContextFactory factory = new UsageContextFactory(repository);

        UsageOperationContext context = factory.successFromLlm(
                "analysis",
                "INTERVIEW",
                "interview-1",
                "operation-1",
                101L,
                new LlmResponse(
                        "{\"status\":\"ok\"}",
                        "db-provider",
                        "db-model",
                        new ProviderUsage(UsageFamily.CHAT, 10L, 5L, 15L, 1L, null)
                )
        );

        assertThat(context.providerId()).isEqualTo(11L);
        assertThat(context.modelId()).isEqualTo(21L);
        assertThat(context.provider()).isEqualTo("db-provider");
        assertThat(context.modelCode()).isEqualTo("db-model");
    }
}
