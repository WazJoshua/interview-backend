package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.resume.service.StructuredExtractionService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredExtractionServiceTest {

    private static final String PURPOSE_PARSE = "parse";
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private AiOperationGateway aiOperationGateway;

    /**
     * Structured extraction should always provide required top-level keys.
     */
    @Test
    void extract_FillsMissingTopLevelKeys() throws Exception {
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        "{\"personalInfo\":{\"name\":\"Josh\"}} ",
                        "default",
                        "qwen3.5-27b",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )));

        StructuredExtractionService service = new StructuredExtractionService(objectMapper, aiOperationGateway);
        String result = service.extract("Email: josh@example.com");

        ArgumentCaptor<AiInvocationInput> requestCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        org.mockito.Mockito.verify(aiOperationGateway).executeInvocation(any(), any(), requestCaptor.capture());
        assertEquals(com.josh.interviewj.llm.gateway.dto.AiInvocationKind.CHAT, requestCaptor.getValue().kind());
        assertTrue(requestCaptor.getValue().userPrompt().contains("josh@example.com"));

        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.has("personalInfo"));
        assertTrue(node.has("education"));
        assertTrue(node.has("workExperience"));
        assertTrue(node.has("skills"));
        assertTrue(node.has("projects"));

        assertEquals("Josh", node.path("personalInfo").path("name").asText());
    }

    @Test
    void extractWithUsage_WhenLlmReturnsInvalidJson_ThrowsBusinessExceptionThatPreservesLlmResponse() {
        LlmResponse llmResponse = new LlmResponse(
                "{\"personalInfo\":",
                "default",
                "qwen3.5-27b",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        );
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(llmResponse));

        StructuredExtractionService service = new StructuredExtractionService(objectMapper, aiOperationGateway);

        StructuredExtractionService.StructuredExtractionException exception = assertThrows(
                StructuredExtractionService.StructuredExtractionException.class,
                () -> service.extractWithUsage(
                        "Email: josh@example.com",
                        new BusinessOperationContext(
                                "biz-1",
                                1L,
                                "RESUME",
                                "resume-1",
                                PURPOSE_PARSE,
                                List.of("RESUME_CREDITS"),
                                Map.of()
                        ),
                        new AiInvocationContext(
                                "biz-1:chat",
                                PURPOSE_PARSE,
                                UsageFamily.CHAT,
                                "RESUME_CREDITS",
                                false,
                                Map.of()
                        )
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo("RESUME_003");
        assertThat(exception.llmResponse()).isSameAs(llmResponse);
    }
}
