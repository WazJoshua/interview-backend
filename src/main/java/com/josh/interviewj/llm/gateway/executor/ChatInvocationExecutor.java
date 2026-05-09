package com.josh.interviewj.llm.gateway.executor;

import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.prompt.dto.PromptTemplateResolution;
import com.josh.interviewj.llm.prompt.service.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChatInvocationExecutor implements AiCapabilityExecutor {

    private final LLMService llmService;
    private final PromptTemplateResolver promptTemplateResolver;

    @Override
    public AiInvocationKind supportsKind() {
        return AiInvocationKind.CHAT;
    }

    @Override
    public AiInvocationResult execute(AiInvocationContext invocationContext, AiInvocationInput input) {
        // Resolve prompts: try template if provided, fallback to Java prompts
        PromptTemplateResolution resolution = promptTemplateResolver.resolve(
                input.promptTemplateRef(),
                input.systemPrompt(),
                input.userPrompt()
        );

        if (resolution.fallbackUsed()) {
            log.info("Using fallback prompt for purpose={}, reason={}",
                    invocationContext.purpose(), resolution.fallbackReason());
        }

        // Execute LLM call with resolved prompts
        LlmResponse response = llmService.generateStructuredJson(
                new LlmRequest(invocationContext.purpose(), resolution.resolvedSystemPrompt(), resolution.resolvedUserPrompt()),
                input.schemaValidator()
        );

        // Build metadata for auditing
        Map<String, Object> metadata = buildMetadata(resolution);

        return AiInvocationResult.fromChat(response, metadata);
    }

    /**
     * Build metadata for usage auditing.
     * Structure: {"promptTemplate":{"templateKey":"...","templateRevision":1,"fallbackUsed":false}}
     */
    private Map<String, Object> buildMetadata(PromptTemplateResolution resolution) {
        Map<String, Object> metadata = new HashMap<>();

        Map<String, Object> promptTemplateMeta = new HashMap<>();
        promptTemplateMeta.put("templateKey", resolution.templateKey());
        promptTemplateMeta.put("templateRevision", resolution.templateRevision());
        promptTemplateMeta.put("fallbackUsed", resolution.fallbackUsed());
        if (resolution.fallbackReason() != null) {
            promptTemplateMeta.put("fallbackReason", resolution.fallbackReason());
        }

        metadata.put("promptTemplate", promptTemplateMeta);
        return metadata;
    }
}