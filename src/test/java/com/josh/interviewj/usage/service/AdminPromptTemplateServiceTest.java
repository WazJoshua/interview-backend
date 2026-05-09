package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.repository.AdminOperationLogRepository;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplate;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplateRevision;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRepository;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRevisionRepository;
import com.josh.interviewj.llm.prompt.service.PromptTemplateRenderer;
import com.josh.interviewj.llm.prompt.service.PromptTemplateValidationService;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateCreateRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplatePreviewRequest;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplatePreviewResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPromptTemplateServiceTest {

    @Mock
    private LlmPromptTemplateRepository templateRepository;
    @Mock
    private LlmPromptTemplateRevisionRepository revisionRepository;
    @Mock
    private LlmConfigChangeService llmConfigChangeService;
    @Mock
    private AdminOperationLogRepository adminOperationLogRepository;
    @Mock
    private PromptTemplateValidationService validationService;
    @Mock
    private PromptTemplateRenderer renderer;

    @InjectMocks
    private AdminPromptTemplateService service;

    @Test
    void createTemplate_Success() {
        when(templateRepository.existsByTemplateKey("test-key")).thenReturn(false);
        when(validationService.validateWriteTemplate(any(), any(), any())).thenReturn(null);
        when(templateRepository.save(any(LlmPromptTemplate.class))).thenAnswer(inv -> {
            LlmPromptTemplate t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(revisionRepository.save(any(LlmPromptTemplateRevision.class))).thenAnswer(inv -> {
            LlmPromptTemplateRevision r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        AdminPromptTemplateCreateRequest request = new AdminPromptTemplateCreateRequest(
                "test-key", "resume", "analysis", "CHAT", "desc", true,
                "system", "user", List.of(new AdminPromptTemplateCreateRequest.VariableDeclaration("name", true)),
                "initial", "admin"
        );

        LlmPromptTemplate result = service.createTemplate(1L, request);

        assertThat(result.getTemplateKey()).isEqualTo("test-key");
        verify(llmConfigChangeService).recordChange(any(), any());
        verify(adminOperationLogRepository).save(any());
    }

    @Test
    void previewDraft_Success() {
        when(validationService.validateWriteTemplate(any(), any(), any())).thenReturn(null);
        when(renderer.render("system", Map.of("name", "test"))).thenReturn("rendered-system");
        when(renderer.render("user", Map.of("name", "test"))).thenReturn("rendered-user");

        AdminPromptTemplatePreviewRequest request = new AdminPromptTemplatePreviewRequest(
                "system", "user", List.of(new AdminPromptTemplatePreviewRequest.VariableDeclaration("name", true)),
                Map.of("name", "test")
        );

        AdminPromptTemplatePreviewResponse result = service.previewDraft(request);

        assertThat(result.success()).isTrue();
        assertThat(result.renderedSystemPrompt()).isEqualTo("rendered-system");
    }
}