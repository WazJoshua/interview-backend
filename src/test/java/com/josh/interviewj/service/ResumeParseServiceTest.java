package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.service.StructuredExtractionResult;
import com.josh.interviewj.resume.service.ResumeParsePersistenceService;
import com.josh.interviewj.resume.service.ResumeParseService;
import com.josh.interviewj.resume.service.StructuredExtractionService;
import com.josh.interviewj.resume.repository.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class ResumeParseServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeParsePersistenceService persistenceService;

    @Mock
    private StructuredExtractionService structuredExtractionService;

    @Mock
    private AiOperationGateway aiOperationGateway;

    /**
     * Parse should persist raw text first, then persist structured content.
     */
    @Test
    void parse_ExtractsRawTextThenSavesParsedContent() {
        when(persistenceService.extractAndSaveRawText(1L)).thenReturn("raw text");
        when(structuredExtractionService.extract("raw text")).thenReturn("{\"personalInfo\":{}}" );

        ResumeParseService service = new ResumeParseService(persistenceService, structuredExtractionService);
        service.parse(1L);

        verify(persistenceService).extractAndSaveRawText(1L);
        verify(structuredExtractionService).extract("raw text");
        verify(persistenceService).saveParsedContent(1L, "{\"personalInfo\":{}}" );
    }

    @Test
    void parse_WithUsageContext_RecordsSuccessAfterSavingParsedContent() {
        Resume resume = Resume.builder()
                .id(1L)
                .userId(7L)
                .externalId(UUID.randomUUID())
                .build();

        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
        when(persistenceService.extractAndSaveRawText(1L)).thenReturn("raw text");
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                7L,
                "RESUME",
                resume.getExternalId().toString(),
                "parse",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(structuredExtractionService.extractWithUsage(any(), any(), any()))
                .thenReturn(new StructuredExtractionResult("{\"personalInfo\":{}}", new LlmResponse("{}", "dispatcher_rc", "gpt-5.4")));

        ResumeParseService service = new ResumeParseService(
                resumeRepository,
                persistenceService,
                structuredExtractionService,
                aiOperationGateway
        );

        service.parse(1L);

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void parse_WhenSaveParsedContentFails_RecordsNonChargeableFailure() {
        Resume resume = Resume.builder()
                .id(1L)
                .userId(7L)
                .externalId(UUID.randomUUID())
                .build();
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
        when(persistenceService.extractAndSaveRawText(1L)).thenReturn("raw text");
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                7L,
                "RESUME",
                resume.getExternalId().toString(),
                "parse",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(structuredExtractionService.extractWithUsage(any(), any(), any()))
                .thenReturn(new StructuredExtractionResult("{\"personalInfo\":{}}", new LlmResponse("{}", "dispatcher_rc", "gpt-5.4")));
        doThrow(new RuntimeException("save failed")).when(persistenceService).saveParsedContent(1L, "{\"personalInfo\":{}}");

        ResumeParseService service = new ResumeParseService(
                resumeRepository,
                persistenceService,
                structuredExtractionService,
                aiOperationGateway
        );

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.parse(1L));

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void parse_WhenStructuredExtractionValidationFails_RecordsNonChargeableFailure() {
        Resume resume = Resume.builder()
                .id(1L)
                .userId(7L)
                .externalId(UUID.randomUUID())
                .build();
        LlmResponse llmResponse = new LlmResponse("{}", "dispatcher_rc", "gpt-5.4");

        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
        when(persistenceService.extractAndSaveRawText(1L)).thenReturn("raw text");
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                7L,
                "RESUME",
                resume.getExternalId().toString(),
                "parse",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(structuredExtractionService.extractWithUsage(any(), any(), any()))
                .thenThrow(new StructuredExtractionService.StructuredExtractionException(
                        "RESUME_003",
                        "Resume parse failed",
                        llmResponse,
                        new RuntimeException("bad json")
                ));

        ResumeParseService service = new ResumeParseService(
                resumeRepository,
                persistenceService,
                structuredExtractionService,
                aiOperationGateway
        );

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () -> service.parse(1L));

        verify(aiOperationGateway).submitInvocationOutcome(
                any(),
                any(),
                any(),
                any(),
                eq(InvocationUsageOutcome.FAILED_NON_CHARGEABLE),
                eq("Resume parse failed")
        );
    }
}
