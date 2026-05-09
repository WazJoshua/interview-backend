package com.josh.interviewj.resume.service;

import com.josh.interviewj.resume.dto.request.ResumeJobMatchCreateRequestDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchCreateResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchDetailResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchListItemResponseDTO;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeJobMatchReport;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeJobMatchReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.resume.prompt.ContentLocalePromptSupport;
import com.josh.interviewj.resume.prompt.ResumeJobMatchPrompts;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates asynchronous resume × JD match workflow and report retrieval.
 */
@Service
@Slf4j
public class ResumeJobMatchService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;
    private static final String PURPOSE_ANALYSIS = "analysis";
    private static final String RESOURCE_TYPE_RESUME = "RESUME";
    private static final String[] EVIDENCE_REQUIRED_TOP_LEVEL_KEYS = {
            "personalInfo",
            "education",
            "workExperience",
            "skills",
            "projects",
            "evidenceQuality"
    };

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisReportRepository analysisReportRepository;
    private final ResumeJobMatchReportRepository matchReportRepository;
    private final UserRepository userRepository;
    private final AiOperationGateway aiOperationGateway;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public ResumeJobMatchService(
            ResumeRepository resumeRepository,
            ResumeAnalysisReportRepository analysisReportRepository,
            ResumeJobMatchReportRepository matchReportRepository,
            UserRepository userRepository,
            AiOperationGateway aiOperationGateway,
            ObjectMapper objectMapper,
            ExecutorService virtualThreadExecutor,
            PlatformTransactionManager transactionManager
    ) {
        this.resumeRepository = resumeRepository;
        this.analysisReportRepository = analysisReportRepository;
        this.matchReportRepository = matchReportRepository;
        this.userRepository = userRepository;
        this.aiOperationGateway = aiOperationGateway;
        this.objectMapper = objectMapper;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.transactionManager = transactionManager;
    }

    /**
     * Validates prerequisites and creates an asynchronous resume-job match task.
     *
     * @param resumeExternalId resume external id
     * @param userId current user id
     * @param request target job payload
     * @return created match task summary
     */
    @Transactional
    public ResumeJobMatchCreateResponseDTO createMatchReport(UUID resumeExternalId, Long userId, ResumeJobMatchCreateRequestDTO request) {
        if (resumeExternalId == null || userId == null) {
            throw new BusinessException(ErrorCode.RESUME_005, "Resume not found");
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.RESUME_001, "Request body is required");
        }

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_005, "Resume not found"));

        if (resume.getStatus() != ResumeStatus.PARSED) {
            throw new BusinessException(ErrorCode.RESUME_009, "Resume must be parsed before matching");
        }

        ResumeAnalysisReport analysisReport = analysisReportRepository.findByResumeIdAndUserId(resume.getId(), userId)
                .orElse(null);
        if (analysisReport == null || analysisReport.getStatus() != AnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RESUME_011, "Resume analysis is not completed");
        }

        // Reuse persisted analysis evidence so the matching prompt stays grounded in validated resume facts.
        String evidenceJson = analysisReport.getEvidenceJson();
        enforceEvidenceOrThrow(evidenceJson);

        String jobTitle = normalizeToNull(request.getJobTitle());
        String jobDescription = normalizeToNull(request.getJobDescription());
        if (jobTitle == null || jobDescription == null) {
            throw new BusinessException(ErrorCode.RESUME_001, "Job title and job description are required");
        }

        ResumeJobMatchReport report = ResumeJobMatchReport.builder()
                .resumeId(resume.getId())
                .userId(userId)
                .jobTitle(jobTitle)
                .jobDescription(jobDescription)
                .contentLocale(resolveUserContentLocale(userId))
                .status(AnalysisStatus.PENDING)
                .promptVersion(ResumeJobMatchPrompts.PROMPT_VERSION)
                .build();

        ResumeJobMatchReport saved = matchReportRepository.save(report);
        // Dispatch the expensive LLM step only after the transaction commits successfully.
        scheduleAfterCommit(saved.getId());

        log.info("Resume job match triggered: matchReportId={}, resumeExternalId={}, userId={}",
                saved.getId(), resumeExternalId, userId);

        return ResumeJobMatchCreateResponseDTO.builder()
                .matchReportId(saved.getId())
                .status(saved.getStatus())
                .build();
    }

    /**
     * Lists visible match reports for a resume owned by the current user.
     *
     * @param resumeExternalId resume external id
     * @param userId current user id
     * @param pageable paging configuration
     * @return paged match report summaries
     */
    @Transactional(readOnly = true)
    public Page<ResumeJobMatchListItemResponseDTO> listMatchReports(UUID resumeExternalId, Long userId, Pageable pageable) {
        if (resumeExternalId == null || userId == null) {
            throw new BusinessException(ErrorCode.RESUME_005, "Resume not found");
        }

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_005, "Resume not found"));

        return matchReportRepository.findByResumeIdAndUserIdAndDeletedAtIsNull(resume.getId(), userId, pageable)
                .map(this::mapToListItem);
    }

    /**
     * Loads a single visible match report for the current user.
     *
     * @param matchReportId match report primary key
     * @param userId current user id
     * @return detailed match report payload
     */
    @Transactional(readOnly = true)
    public ResumeJobMatchDetailResponseDTO getMatchReport(Long matchReportId, Long userId) {
        if (matchReportId == null || userId == null) {
            throw new BusinessException(ErrorCode.RESUME_012, "Match report not found");
        }

        ResumeJobMatchReport visible = matchReportRepository.findByIdAndUserIdAndDeletedAtIsNull(matchReportId, userId)
                .orElse(null);
        if (visible != null) {
            return mapToDetail(visible);
        }

        ResumeJobMatchReport any = matchReportRepository.findByIdAndUserId(matchReportId, userId).orElse(null);
        if (any != null && any.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RESUME_013, "Match report is deleted");
        }
        throw new BusinessException(ErrorCode.RESUME_012, "Match report not found");
    }

    /**
     * Soft deletes a match report if it belongs to the current user.
     *
     * @param matchReportId match report primary key
     * @param userId current user id
     */
    @Transactional
    public void deleteMatchReport(Long matchReportId, Long userId) {
        if (matchReportId == null || userId == null) {
            throw new BusinessException(ErrorCode.RESUME_012, "Match report not found");
        }

        int updated = matchReportRepository.softDeleteByIdAndUserId(matchReportId, userId);
        if (updated > 0) {
            return;
        }

        ResumeJobMatchReport any = matchReportRepository.findByIdAndUserId(matchReportId, userId).orElse(null);
        if (any != null && any.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RESUME_013, "Match report is deleted");
        }

        throw new BusinessException(ErrorCode.RESUME_012, "Match report not found");
    }

    /**
     * Executes the asynchronous LLM matching job for a previously created report.
     *
     * @param matchReportId match report primary key
     */
    public void performMatch(Long matchReportId) {
        if (matchReportId == null) {
            return;
        }

        MatchContext ctx = loadContextOrFail(matchReportId);
        if (ctx == null) {
            return;
        }

        BusinessOperationContext operationContext = null;
        AiInvocationContext invocationContext = null;
        AiInvocationResult invocationResult = null;
        try {
            String businessOperationId = "resume-job-match-" + matchReportId + "-" + UUID.randomUUID();
            // Build a tightly scoped prompt from validated evidence plus the requested JD.
            String userPrompt = ResumeJobMatchPrompts.buildUserPrompt(
                    ctx.evidenceJson(),
                    ctx.jobTitle(),
                    ctx.jobDescription(),
                    ctx.contentLocale()
            );
            operationContext = aiOperationGateway.prepareOperation(new BusinessOperationContext(
                    businessOperationId,
                    ctx.userId(),
                    RESOURCE_TYPE_RESUME,
                    ctx.resumeExternalId().toString(),
                    PURPOSE_ANALYSIS,
                    List.of("RESUME_CREDITS"),
                    Collections.emptyMap()
            ));
            invocationContext = new AiInvocationContext(
                    businessOperationId + ":chat",
                    PURPOSE_ANALYSIS,
                    UsageFamily.CHAT,
                    "RESUME_CREDITS",
                    false,
                    Collections.emptyMap()
            );
            invocationResult = aiOperationGateway.executeInvocation(
                    operationContext,
                    invocationContext,
                    AiInvocationInput.chat(ResumeJobMatchPrompts.SYSTEM_PROMPT, userPrompt, null,
                            new PromptTemplateRef("resume_job_match", buildJobMatchTemplateVariables(ctx)))
            );
            LlmResponse response = invocationResult.llmResponse();

            String outputJson = response.content();
            enforceJsonObject(outputJson, "Match output");

            // Persist only normalized JSON fields so downstream readers can trust the stored schema.
            MatchOutput output = parseMatchOutput(outputJson);
            persistSuccess(matchReportId, output, response.model());
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    invocationContext,
                    invocationResult,
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );
            log.info("Resume job match completed: matchReportId={}, matchScore={}", matchReportId, output.matchScore());
        } catch (Exception e) {
            log.warn("Resume job match failed: matchReportId={}, errorType={}", matchReportId, e.getClass().getSimpleName());
            if (operationContext != null && invocationContext != null && invocationResult != null) {
                aiOperationGateway.submitInvocationOutcome(
                        operationContext,
                        invocationContext,
                        invocationResult,
                        ExecutionDisposition.EXECUTED,
                        InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                        safeErrorMessage(e)
                );
            }
            persistFailure(matchReportId, safeErrorMessage(e));
        }
    }

    /**
     * Loads the report and marks it as analyzing inside a dedicated transaction.
     *
     * @param matchReportId match report primary key
     * @return immutable context for the async worker, or {@code null} when the job should stop
     */
    private MatchContext loadContextOrFail(Long matchReportId) {
        return requiresNewTx().execute(status -> {
            ResumeJobMatchReport report = matchReportRepository.findById(matchReportId).orElse(null);
            if (report == null) {
                log.warn("Match report not found: matchReportId={}", matchReportId);
                return null;
            }
            if (report.getDeletedAt() != null) {
                return null;
            }

            if (report.getContentLocale() == null) {
                report.setContentLocale(ContentLocale.DEFAULT.getTag());
                matchReportRepository.save(report);
            }

            matchReportRepository.updateStatus(matchReportId, AnalysisStatus.ANALYZING);

            Resume resume = resumeRepository.findById(report.getResumeId()).orElse(null);
            if (resume == null) {
                report.setStatus(AnalysisStatus.FAILED);
                report.setErrorMessage("Resume not found");
                report.setCompletedAt(LocalDateTime.now());
                matchReportRepository.save(report);
                return null;
            }

            ResumeAnalysisReport analysisReport = analysisReportRepository.findByResumeIdAndUserId(resume.getId(), report.getUserId())
                    .orElse(null);
            if (analysisReport == null || analysisReport.getStatus() != AnalysisStatus.COMPLETED) {
                report.setStatus(AnalysisStatus.FAILED);
                report.setErrorMessage("Resume analysis is not completed");
                report.setCompletedAt(LocalDateTime.now());
                matchReportRepository.save(report);
                return null;
            }

            String evidenceJson = analysisReport.getEvidenceJson();
            try {
                enforceEvidenceOrThrow(evidenceJson);
            } catch (Exception ex) {
                report.setStatus(AnalysisStatus.FAILED);
                report.setErrorMessage(safeErrorMessage(ex));
                report.setCompletedAt(LocalDateTime.now());
                matchReportRepository.save(report);
                return null;
            }

            return new MatchContext(
                    report.getUserId(),
                    resume.getExternalId(),
                    report.getJobTitle(),
                    report.getJobDescription(),
                    evidenceJson,
                    report.getContentLocale()
            );
        });
    }

    /**
     * Persists a successful LLM match result.
     *
     * @param matchReportId match report primary key
     * @param output normalized LLM output
     * @param modelName resolved model name
     */
    private void persistSuccess(Long matchReportId, MatchOutput output, String modelName) {
        requiresNewTx().executeWithoutResult(status -> {
            ResumeJobMatchReport report = matchReportRepository.findById(matchReportId).orElse(null);
            if (report == null || report.getDeletedAt() != null) {
                return;
            }

            report.setMatchScore(output.matchScore());
            report.setSummary(output.summary());
            report.setStrengthsJson(output.strengthsJson());
            report.setGapsJson(output.gapsJson());
            report.setSuggestionsJson(output.suggestionsJson());
            report.setPromptVersion(ResumeJobMatchPrompts.PROMPT_VERSION);
            report.setModelName(modelName);
            report.setStatus(AnalysisStatus.COMPLETED);
            report.setCompletedAt(LocalDateTime.now());
            report.setErrorMessage(null);
            matchReportRepository.save(report);
        });
    }

    /**
     * Persists a terminal failure for the async match task.
     *
     * @param matchReportId match report primary key
     * @param errorMessage safe error message
     */
    private void persistFailure(Long matchReportId, String errorMessage) {
        requiresNewTx().executeWithoutResult(status -> {
            ResumeJobMatchReport report = matchReportRepository.findById(matchReportId).orElse(null);
            if (report == null || report.getDeletedAt() != null) {
                return;
            }
            report.setStatus(AnalysisStatus.FAILED);
            report.setErrorMessage(errorMessage);
            report.setCompletedAt(LocalDateTime.now());
            matchReportRepository.save(report);
        });
    }

    /**
     * Schedules async execution after the surrounding transaction commits.
     *
     * @param matchReportId match report primary key
     */
    private void scheduleAfterCommit(Long matchReportId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    virtualThreadExecutor.submit(() -> performMatch(matchReportId));
                }
            });
            return;
        }

        virtualThreadExecutor.submit(() -> performMatch(matchReportId));
    }

    /**
     * Builds a helper transaction template that always runs in a new transaction.
     *
     * @return transaction template with {@code REQUIRES_NEW}
     */
    private TransactionTemplate requiresNewTx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Ensures a raw LLM payload is a JSON object before field extraction.
     *
     * @param json raw JSON payload
     * @param context error context
     * @throws Exception when the payload cannot be parsed or is not an object
     */
    private void enforceJsonObject(String json, String context) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject()) {
            throw new IllegalStateException(context + " must be a JSON object");
        }
    }

    /**
     * Verifies that resume analysis evidence contains the required top-level sections.
     *
     * @param evidenceJson persisted evidence JSON
     */
    private void enforceEvidenceOrThrow(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_014, "Resume analysis evidence is missing");
        }

        try {
            JsonNode root = objectMapper.readTree(evidenceJson);
            if (!root.isObject()) {
                throw new BusinessException(ErrorCode.RESUME_014, "Resume analysis evidence must be a JSON object");
            }
            for (String key : EVIDENCE_REQUIRED_TOP_LEVEL_KEYS) {
                if (!root.has(key) || root.get(key).isNull()) {
                    throw new BusinessException(ErrorCode.RESUME_014, "Resume analysis evidence is missing key: " + key);
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RESUME_014, "Resume analysis evidence is invalid");
        }
    }

    /**
     * Parses and normalizes the structured LLM match output.
     *
     * @param json raw JSON payload
     * @return normalized match output
     * @throws Exception when parsing fails
     */
    private MatchOutput parseMatchOutput(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        if (!root.path("matchScore").isNumber()) {
            throw new IllegalStateException("Missing numeric matchScore");
        }
        int matchScore = root.path("matchScore").asInt();
        matchScore = clampScore(matchScore);

        String summary = normalizeToNull(root.path("summary").asText(null));
        if (summary == null) {
            summary = "";
        }

        String strengthsJson = canonicalizeStringArrayOrEmpty(root.path("strengths"));
        String gapsJson = canonicalizeStringArrayOrEmpty(root.path("gaps"));
        String suggestionsJson = canonicalizeStringArrayOrEmpty(root.path("suggestions"));

        return new MatchOutput(matchScore, summary, strengthsJson, gapsJson, suggestionsJson);
    }

    private Map<String, Object> buildJobMatchTemplateVariables(MatchContext ctx) {
        return Map.of(
                "evidenceJson", ctx.evidenceJson(),
                "jobTitle", ctx.jobTitle(),
                "jobDescription", ctx.jobDescription(),
                "contentLocaleInstruction", ContentLocalePromptSupport.buildUserFacingOutputInstruction(
                        ctx.contentLocale(),
                        "summary, strengths, gaps, and suggestions"
                )
        );
    }

    /**
     * Converts an optional JSON array into a canonical JSON string array.
     *
     * @param node source JSON node
     * @return serialized JSON array string
     * @throws Exception when serialization fails
     */
    private String canonicalizeStringArrayOrEmpty(JsonNode node) throws Exception {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "[]";
        }
        if (!node.isArray()) {
            return "[]";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String text = normalizeToNull(item.asText());
                if (text != null) {
                    values.add(text);
                }
            }
        }
        return objectMapper.writeValueAsString(values);
    }

    /**
     * Maps a persisted report into the lightweight list view.
     *
     * @param report persisted entity
     * @return list item DTO
     */
    private ResumeJobMatchListItemResponseDTO mapToListItem(ResumeJobMatchReport report) {
        return ResumeJobMatchListItemResponseDTO.builder()
                .matchReportId(report.getId())
                .status(report.getStatus())
                .contentLocale(report.getContentLocale())
                .matchScore(report.getMatchScore())
                .summary(report.getSummary())
                .createdAt(report.getCreatedAt())
                .completedAt(report.getCompletedAt())
                .build();
    }

    /**
     * Maps a persisted report into the detail view payload.
     *
     * @param report persisted entity
     * @return detailed DTO
     */
    private ResumeJobMatchDetailResponseDTO mapToDetail(ResumeJobMatchReport report) {
        return ResumeJobMatchDetailResponseDTO.builder()
                .matchReportId(report.getId())
                .jobTitle(report.getJobTitle())
                .jobDescription(report.getJobDescription())
                .status(report.getStatus())
                .contentLocale(report.getContentLocale())
                .matchScore(report.getMatchScore())
                .summary(report.getSummary())
                .strengths(parseStringArray(report.getStrengthsJson()))
                .gaps(parseStringArray(report.getGapsJson()))
                .suggestions(parseStringArray(report.getSuggestionsJson()))
                .promptVersion(report.getPromptVersion())
                .modelName(report.getModelName())
                .createdAt(report.getCreatedAt())
                .completedAt(report.getCompletedAt())
                .errorMessage(report.getErrorMessage())
                .build();
    }

    /**
     * Parses a persisted JSON array into a list of strings.
     *
     * @param json serialized JSON array
     * @return parsed values or an empty list when invalid
     */
    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return Collections.emptyList();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && item.isTextual()) {
                    String text = normalizeToNull(item.asText());
                    if (text != null) {
                        values.add(text);
                    }
                }
            }
            return values;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Clamps a match score to the supported 0-100 range.
     *
     * @param score raw score
     * @return bounded score
     */
    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        if (score > 100) {
            return 100;
        }
        return score;
    }

    /**
     * Trims a string and converts blanks to {@code null}.
     *
     * @param value raw value
     * @return normalized value
     */
    private static String normalizeToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Produces a bounded error message that is safe to persist and expose.
     *
     * @param e source exception
     * @return sanitized error message
     */
    private static String safeErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "Resume job match failed";
        }
        msg = msg.trim();
        if (msg.length() > ERROR_MESSAGE_MAX_LENGTH) {
            msg = msg.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        }
        return msg;
    }

    private String resolveUserContentLocale(Long userId) {
        return userRepository.findById(userId)
                .map(user -> ContentLocale.normalizeOrDefault(user.getLocale()))
                .orElse(ContentLocale.DEFAULT.getTag());
    }

    private record MatchContext(
            Long userId,
            UUID resumeExternalId,
            String jobTitle,
            String jobDescription,
            String evidenceJson,
            String contentLocale
    ) {
    }

    private record MatchOutput(
            int matchScore,
            String summary,
            String strengthsJson,
            String gapsJson,
            String suggestionsJson
    ) {
    }
}
