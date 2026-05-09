package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.executor.ChatInvocationExecutor;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateResolution;
import com.josh.interviewj.llm.prompt.service.PromptTemplateResolver;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatInvocationExecutorTest {

    @Test
    void execute_DelegatesPromptsPurposeAndSchemaValidator() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateResolver promptTemplateResolver = mock(PromptTemplateResolver.class);
        ChatInvocationExecutor executor = new ChatInvocationExecutor(llmService, promptTemplateResolver);

        AtomicReference<String> validated = new AtomicReference<>();
        AiInvocationContext invocationContext = new AiInvocationContext(
                "inv-1",
                "analysis",
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Map.of()
        );
        AiInvocationInput input = AiInvocationInput.chat("system", "user", validated::set);

        // Resolver returns fallback (no template ref)
        when(promptTemplateResolver.resolve(eq(null), eq("system"), eq("user")))
                .thenReturn(PromptTemplateResolution.fallback("system", "user", "No template reference provided"));

        LlmResponse response = new LlmResponse(
                "{\"score\":1}",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        );
        when(llmService.generateStructuredJson(any(LlmRequest.class), any(Consumer.class))).thenAnswer(invocation -> {
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("{\"score\":1}");
            return response;
        });

        AiInvocationResult result = executor.execute(invocationContext, input);

        assertThat(validated.get()).isEqualTo("{\"score\":1}");
        assertThat(result.provider()).isEqualTo("default");
        assertThat(result.model()).isEqualTo("qwen-plus");
        assertThat(result.usage()).isEqualTo(response.usage());
        assertThat(result.metadata()).containsKey("promptTemplate");
        assertThat(result.metadata().get("promptTemplate")).isInstanceOf(Map.class);
        Map<String, Object> promptMeta = (Map<String, Object>) result.metadata().get("promptTemplate");
        assertThat(promptMeta.get("fallbackUsed")).isEqualTo(true);
        verify(llmService).generateStructuredJson(any(LlmRequest.class), any(Consumer.class));
    }
}