package com.josh.interviewj.resume.service;

import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class ResumeParseService {

    private static final String RESOURCE_TYPE_RESUME = "RESUME";

    private final ResumeRepository resumeRepository;
    private final ResumeParsePersistenceService persistenceService;
    private final StructuredExtractionService structuredExtractionService;
    private final AiOperationGateway aiOperationGateway;

    @Autowired
    public ResumeParseService(
            ResumeRepository resumeRepository,
            ResumeParsePersistenceService persistenceService,
            StructuredExtractionService structuredExtractionService,
            AiOperationGateway aiOperationGateway
    ) {
        this.resumeRepository = resumeRepository;
        this.persistenceService = persistenceService;
        this.structuredExtractionService = structuredExtractionService;
        this.aiOperationGateway = aiOperationGateway;
    }

    public ResumeParseService(
            ResumeParsePersistenceService persistenceService,
            StructuredExtractionService structuredExtractionService
    ) {
        this(null, persistenceService, structuredExtractionService, null);
    }

    /**
     * Parse a resume by id.
     *
     * <p>Design intent: keep DB transactions short.
     * Raw text persistence and parsed content persistence are transactional, while LLM extraction
     * happens outside of DB transactions.</p>
     *
     * @param resumeId resume primary key
     */
    public void parse(Long resumeId) {
        if (resumeRepository == null || aiOperationGateway == null) {
            String rawText = persistenceService.extractAndSaveRawText(resumeId);
            String parsedContent = structuredExtractionService.extract(rawText);
            persistenceService.saveParsedContent(resumeId, parsedContent);
            return;
        }

        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new com.josh.interviewj.common.exception.BusinessException("RESUME_005", "Resume not found"));
        String businessOperationId = "resume-parse-" + UUID.randomUUID();
        BusinessOperationContext operationContext = aiOperationGateway.prepareOperation(new BusinessOperationContext(
                businessOperationId,
                resume.getUserId(),
                RESOURCE_TYPE_RESUME,
                resume.getExternalId().toString(),
                "parse",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        AiInvocationContext invocationContext = new AiInvocationContext(
                businessOperationId + ":chat",
                "parse",
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Map.of()
        );

        String rawText = persistenceService.extractAndSaveRawText(resumeId);
        StructuredExtractionResult extractionResult;
        try {
            extractionResult = structuredExtractionService.extractWithUsage(
                    rawText,
                    operationContext,
                    invocationContext
            );
        } catch (StructuredExtractionService.StructuredExtractionException exception) {
            if (exception.llmResponse() != null) {
                aiOperationGateway.submitInvocationOutcome(
                        operationContext,
                        invocationContext,
                        AiInvocationResult.fromChat(exception.llmResponse()),
                        ExecutionDisposition.EXECUTED,
                        InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                        exception.getMessage()
                );
            }
            throw exception;
        }

        try {
            persistenceService.saveParsedContent(resumeId, extractionResult.parsedContent());
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    invocationContext,
                    AiInvocationResult.fromChat(extractionResult.llmResponse()),
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );
        } catch (RuntimeException exception) {
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    invocationContext,
                    AiInvocationResult.fromChat(extractionResult.llmResponse()),
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                    exception.getMessage()
            );
            throw exception;
        }
    }
}
