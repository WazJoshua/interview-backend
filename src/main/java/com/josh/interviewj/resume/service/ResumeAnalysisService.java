package com.josh.interviewj.resume.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.resume.dto.response.ResumeAnalysisResponseDTO;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.prompt.ContentLocalePromptSupport;
import com.josh.interviewj.resume.prompt.ResumeAnalysisPrompts;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates resume analysis trigger, synchronous execution, and report retrieval.
 */
@Service
@Slf4j
public class ResumeAnalysisService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;
    private static final String PURPOSE_ANALYSIS = "analysis";
    private static final String RESOURCE_TYPE_RESUME_ANALYSIS_REPORT = "RESUME_ANALYSIS_REPORT";
    private static final String[] EVIDENCE_REQUIRED_TOP_LEVEL_KEYS = {
            "personalInfo",
            "education",
            "workExperience",
            "skills",
            "projects",
            "evidenceQuality"
    };

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisReportRepository reportRepository;
    private final ResumeAnalysisOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final AiOperationGateway aiOperationGateway;
    private final ObjectMapper objectMapper;

    @Autowired
    public ResumeAnalysisService(
            ResumeRepository resumeRepository,
            ResumeAnalysisReportRepository reportRepository,
            ResumeAnalysisOutboxRepository outboxRepository,
            UserRepository userRepository,
            AiOperationGateway aiOperationGateway,
            ObjectMapper objectMapper
    ) {
        this.resumeRepository = resumeRepository;
        this.reportRepository = reportRepository;
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.aiOperationGateway = aiOperationGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates or reuses an analysis report, then persists a reliable outbox task in the same transaction.
     *
     * @param resumeExternalId resume external id
     * @param userId internal user id
     * @return report id
     */
    @Transactional
    public Long triggerAnalysis(UUID resumeExternalId, Long userId) {
        if (resumeExternalId == null || userId == null) {
            throw new BusinessException("RESUME_005", "Resume not found");
        }

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId)
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        if (resume.getStatus() != ResumeStatus.PARSED) {
            throw new BusinessException("RESUME_009", "Resume must be parsed before analysis");
        }

        ResumeAnalysisReport existing = reportRepository.findByResumeIdAndUserId(resume.getId(), userId).orElse(null);
        if (existing != null && (existing.getStatus() == AnalysisStatus.PENDING || existing.getStatus() == AnalysisStatus.ANALYZING)) {
            log.info("Resume analysis already in progress, reuse existing report: reportId={}, resumeExternalId={}, userId={}",
                    existing.getId(), resumeExternalId, userId);
            return existing.getId();
        }

        ResumeAnalysisReport report = existing == null
                ? ResumeAnalysisReport.builder()
                .resumeId(resume.getId())
                .userId(userId)
                .build()
                : existing;

        report.setStatus(AnalysisStatus.PENDING);
        report.setPromptVersion(ResumeAnalysisPrompts.FRAMEWORK_VERSION);
        report.setContentLocale(resolveUserContentLocale(userId));
        resetReportForRerun(report);

        ResumeAnalysisReport saved = reportRepository.save(report);
        resume.setAnalysisStatus(AnalysisStatus.PENDING);
        resume.setErrorMessage(null);
        resumeRepository.save(resume);
        outboxRepository.save(ResumeAnalysisOutbox.builder()
                .reportId(saved.getId())
                .resumeId(resume.getId())
                .resumeExternalId(resume.getExternalId())
                .build());

        log.info("Resume analysis triggered: reportId={}, resumeExternalId={}, userId={}", saved.getId(), resumeExternalId, userId);
        return saved.getId();
    }

    /**
     * Executes analysis directly for compatibility and manual verification paths.
     *
     * @param reportId report id
     */
    public void performAnalysis(Long reportId) {
        if (reportId == null) {
            return;
        }

        try {
            executeAnalysisAndFinalize(reportId);
        } catch (Exception exception) {
            throw classifyExecutionException(exception);
        }
    }

    /**
     * Executes the two-stage analysis synchronously and persists the completed result in the same transaction.
     *
     * @param reportId report id
     * @throws Exception when analysis execution or validation fails
     */
    @Transactional
    public void executeAnalysisAndFinalize(Long reportId) throws Exception {
        AnalysisContext ctx = loadContextOrFail(reportId);
        Instant analysisStartedAt = Instant.now();
        String resourceExternalId = ctx.resumeExternalId().toString();
        String businessOperationId = "resume-analysis-" + reportId + "-" + UUID.randomUUID();
        BusinessOperationContext operationContext = aiOperationGateway.prepareOperation(new BusinessOperationContext(
                businessOperationId,
                ctx.userId(),
                RESOURCE_TYPE_RESUME_ANALYSIS_REPORT,
                resourceExternalId,
                PURPOSE_ANALYSIS,
                List.of("RESUME_CREDITS"),
                Collections.emptyMap()
        ));
        AiInvocationContext stageAInvocation = new AiInvocationContext(
                businessOperationId + ":stage-a",
                PURPOSE_ANALYSIS,
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Collections.emptyMap()
        );
        AiInvocationContext stageBInvocation = new AiInvocationContext(
                businessOperationId + ":stage-b",
                PURPOSE_ANALYSIS,
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Collections.emptyMap()
        );
        AiInvocationResult stageAResult = null;
        AiInvocationResult stageBResult = null;
        boolean stageASubmitted = false;

        try {
            String stageAUserPrompt = ResumeAnalysisPrompts.buildStageAUserPrompt(ctx.rawText(), ctx.parsedContent());
            Instant stageAStartedAt = Instant.now();
            log.info("Resume analysis stage started: reportId={}, resumeExternalId={}, stage={}, promptChars={}",
                    reportId, ctx.resumeExternalId(), "stage_a_llm", stageAUserPrompt.length());

            stageAResult = aiOperationGateway.executeInvocation(
                    operationContext,
                    stageAInvocation,
                    AiInvocationInput.chat(ResumeAnalysisPrompts.STAGE_A_SYSTEM_PROMPT, stageAUserPrompt, null,
                            new PromptTemplateRef("resume_analysis_stage_a", buildStageATemplateVariables(ctx)))
            );
            LlmResponse stageAResponse = stageAResult.llmResponse();
            String evidenceJson = stageAResponse.content();
            logStageCompleted(reportId, ctx.resumeExternalId(), "stage_a_llm", stageAStartedAt, stageAResponse.model(), evidenceJson.length());

            enforceJsonObject(evidenceJson, "Stage A evidence");
            if (!ResumeAnalysisPrompts.isStageAOutputValid(evidenceJson)) {
                throw new IllegalStateException("Stage A output exceeds size limits");
            }
            enforceEvidenceSchema(evidenceJson);
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    stageAInvocation,
                    stageAResult,
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );
            stageASubmitted = true;

            ResumeEvidence evidence = objectMapper.readValue(evidenceJson, ResumeEvidence.class);
            String evidenceForPrompt = objectMapper.writeValueAsString(evidence);

            String stageBUserPrompt = ResumeAnalysisPrompts.buildStageBUserPrompt(evidenceForPrompt, ctx.contentLocale());
            Instant stageBStartedAt = Instant.now();
            log.info("Resume analysis stage started: reportId={}, resumeExternalId={}, stage={}, promptChars={}",
                    reportId, ctx.resumeExternalId(), "stage_b_llm", stageBUserPrompt.length());

            stageBResult = aiOperationGateway.executeInvocation(
                    operationContext,
                    stageBInvocation,
                    AiInvocationInput.chat(ResumeAnalysisPrompts.STAGE_B_SYSTEM_PROMPT, stageBUserPrompt, null,
                            new PromptTemplateRef("resume_analysis_stage_b", buildStageBTemplateVariables(ctx, evidenceForPrompt)))
            );
            LlmResponse stageBResponse = stageBResult.llmResponse();
            String scoringJson = stageBResponse.content();
            logStageCompleted(reportId, ctx.resumeExternalId(), "stage_b_llm", stageBStartedAt, stageBResponse.model(), scoringJson.length());

            enforceJsonObject(scoringJson, "Stage B scoring");
            ScoringResult scoring = objectMapper.readValue(scoringJson, ScoringResult.class);
            AnalysisResult result = computeAndExtractResult(scoring);
            persistSuccess(reportId, ctx.resumeId(), evidenceJson, result, stageBResponse.model());
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    stageBInvocation,
                    stageBResult,
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );

            log.info("Resume analysis completed: reportId={}, resumeExternalId={}, overallScore={}, totalElapsedMs={}",
                    reportId, ctx.resumeExternalId(), result.overallScore(), elapsedMillis(analysisStartedAt));
        } catch (Exception exception) {
            if (stageAResult != null && !stageASubmitted) {
                aiOperationGateway.submitInvocationOutcome(
                        operationContext,
                        stageAInvocation,
                        stageAResult,
                        ExecutionDisposition.EXECUTED,
                        InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                        safeErrorMessage(exception)
                );
            }
            if (stageBResult != null) {
                aiOperationGateway.submitInvocationOutcome(
                        operationContext,
                        stageBInvocation,
                        stageBResult,
                        ExecutionDisposition.EXECUTED,
                        InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                        safeErrorMessage(exception)
                );
            }
            throw exception;
        }
    }

    /**
     * Loads analysis report by resume external id for a specific user.
     *
     * @param resumeExternalId resume external id
     * @param userId internal user id
     * @return analysis report response
     */
    @Transactional(readOnly = true)
    public ResumeAnalysisResponseDTO getAnalysisReport(UUID resumeExternalId, Long userId) {
        if (resumeExternalId == null || userId == null) {
            throw new BusinessException("RESUME_005", "Resume not found");
        }

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId)
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        ResumeAnalysisReport report = reportRepository.findByResumeIdAndUserId(resume.getId(), userId)
                .orElseThrow(() -> new BusinessException("RESUME_010", "Resume analysis report not found"));

        return mapToResponseDTO(resume, report);
    }

    /**
     * Moves the report and resume back to pending state, increments retry count, and creates a new outbox row.
     *
     * @param reportId     report id
     * @param errorMessage safe retry message
     */
    @Transactional
    public void handleRetryableFailure(Long reportId, String errorMessage, Long retrySourceOutboxId) {
        ResumeAnalysisReport report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            return;
        }

        Resume resume = resumeRepository.findById(report.getResumeId()).orElse(null);
        if (resume == null) {
            return;
        }

        if (retrySourceOutboxId != null && outboxRepository.existsByRetrySourceOutboxId(retrySourceOutboxId)) {
            return;
        }

        int currentRetryCount = report.getRetryCount() == null ? 0 : report.getRetryCount();
        resetReportForRerun(report);
        report.setStatus(AnalysisStatus.PENDING);
        report.setRetryCount(currentRetryCount + 1);
        report.setErrorMessage(errorMessage);
        reportRepository.save(report);
        resume.setAnalysisStatus(AnalysisStatus.PENDING);
        resume.setErrorMessage(errorMessage);
        resumeRepository.save(resume);
        outboxRepository.save(ResumeAnalysisOutbox.builder()
                .reportId(reportId)
                .resumeId(resume.getId())
                .resumeExternalId(resume.getExternalId())
                .retrySourceOutboxId(retrySourceOutboxId)
                .build());
    }

    /**
     * Marks the report and resume as terminally failed.
     *
     * @param reportId     report id
     * @param errorMessage safe failure message
     */
    @Transactional
    public void handleTerminalFailure(Long reportId, String errorMessage) {
        ResumeAnalysisReport report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            return;
        }

        report.setStatus(AnalysisStatus.FAILED);
        report.setErrorMessage(errorMessage);
        report.setCompletedAt(LocalDateTime.now());
        reportRepository.save(report);
        Resume resume = resumeRepository.findById(report.getResumeId()).orElse(null);
        if (resume != null) {
            resume.setAnalysisStatus(AnalysisStatus.FAILED);
            resume.setErrorMessage(errorMessage);
            resumeRepository.save(resume);
        }
    }

    /**
     * Converts persisted resume/report entities into API response payload.
     *
     * @param resume resume entity
     * @param report analysis report entity
     * @return response dto
     */
    private ResumeAnalysisResponseDTO mapToResponseDTO(Resume resume, ResumeAnalysisReport report) {
        List<ResumeAnalysisResponseDTO.ImprovementSuggestion> suggestions = parseImprovementSuggestions(report.getImprovementSuggestionsJson());
        List<ResumeAnalysisResponseDTO.SectionAnalysis> sectionAnalysis = parseSectionAnalysis(report.getSectionAnalysisJson());

        return ResumeAnalysisResponseDTO.builder()
                .reportId(report.getId())
                .resumeId(resume.getExternalId())
                .status(report.getStatus())
                .contentLocale(report.getContentLocale())
                .scores(ResumeAnalysisResponseDTO.Scores.builder()
                        .completeness(report.getCompletenessScore())
                        .clarity(report.getClarityScore())
                        .overall(report.getOverallScore())
                        .build())
                .summary(report.getSummary())
                .improvementSuggestions(suggestions)
                .sectionAnalysis(sectionAnalysis)
                .createdAt(report.getCreatedAt())
                .completedAt(report.getCompletedAt())
                .errorMessage(report.getErrorMessage())
                .build();
    }

    /**
     * Parses persisted improvement suggestions JSON into response DTOs.
     *
     * @param json serialized suggestions JSON
     * @return parsed suggestions or an empty list when invalid
     */
    private List<ResumeAnalysisResponseDTO.ImprovementSuggestion> parseImprovementSuggestions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return Collections.emptyList();
            }
            List<ResumeAnalysisResponseDTO.ImprovementSuggestion> result = new ArrayList<>();
            for (JsonNode node : root) {
                result.add(ResumeAnalysisResponseDTO.ImprovementSuggestion.builder()
                        .category(asTextOrNull(node.path("category")))
                        .priority(asTextOrNull(node.path("priority")))
                        .suggestion(asTextOrNull(node.path("suggestion")))
                        .example(asTextOrNull(node.path("example")))
                        .section(asTextOrNull(node.path("section")))
                        .build());
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Parses persisted section analysis JSON into response DTOs.
     *
     * @param json serialized section analysis JSON
     * @return parsed section analysis or an empty list when invalid
     */
    private List<ResumeAnalysisResponseDTO.SectionAnalysis> parseSectionAnalysis(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return Collections.emptyList();
            }
            List<ResumeAnalysisResponseDTO.SectionAnalysis> result = new ArrayList<>();
            for (JsonNode node : root) {
                String sectionName = asTextOrNull(node.path("sectionName"));
                if (sectionName == null) {
                    sectionName = asTextOrNull(node.path("section"));
                }
                result.add(ResumeAnalysisResponseDTO.SectionAnalysis.builder()
                        .sectionName(sectionName)
                        .score(node.path("score").isNumber() ? node.path("score").asInt() : null)
                        .feedback(asTextOrNull(node.path("feedback")))
                        .strengths(readStringArray(node.path("strengths")))
                        .weaknesses(readStringArray(node.path("weaknesses")))
                        .suggestions(readStringArray(node.path("suggestions")))
                        .build());
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Logs completion metadata for an analysis stage.
     *
     * @param reportId         analysis report id
     * @param resumeExternalId resume external id
     * @param stage            stage name
     * @param stageStartedAt   stage start time
     * @param modelName        model name used for the stage
     * @param responseChars    response length
     */
    private void logStageCompleted(
            Long reportId,
            UUID resumeExternalId,
            String stage,
            Instant stageStartedAt,
            String modelName,
            int responseChars
    ) {
        log.info("Resume analysis stage completed: reportId={}, resumeExternalId={}, stage={}, elapsedMs={}, model={}, responseChars={}",
                reportId,
                resumeExternalId,
                stage,
                elapsedMillis(stageStartedAt),
                modelName,
                responseChars);
    }

    private Map<String, Object> buildStageATemplateVariables(AnalysisContext ctx) {
        return Map.of(
                "resumeText", ResumeAnalysisPrompts.truncateToStageALimit(ctx.rawText()),
                "parsedHintsSection", buildParsedHintsSection(ctx.parsedContent())
        );
    }

    private Map<String, Object> buildStageBTemplateVariables(AnalysisContext ctx, String evidenceForPrompt) {
        return Map.of(
                "resumeEvidence", evidenceForPrompt,
                "contentLocaleInstruction", ContentLocalePromptSupport.buildUserFacingOutputInstruction(
                        ctx.contentLocale(),
                        "summary, improvementSuggestions, and sectionAnalysis.feedback"
                )
        );
    }

    private String buildParsedHintsSection(String parsedContent) {
        if (parsedContent == null || parsedContent.isBlank()) {
            return "";
        }
        return "PARSED HINTS:\n" + parsedContent + "\n\n";
    }

    /**
     * Calculates elapsed milliseconds from the given start instant until now.
     *
     * @param startedAt start instant
     * @return elapsed milliseconds
     */
    private long elapsedMillis(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    /**
     * Loads report and resume context required for analysis execution.
     *
     * @param reportId report id
     * @return execution context
     */
    private AnalysisContext loadContextOrFail(Long reportId) {
        ResumeAnalysisReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("RESUME_010", "Resume analysis report not found"));
        String contentLocale = report.getContentLocale();
        if (contentLocale == null) {
            contentLocale = ContentLocale.DEFAULT.getTag();
            report.setContentLocale(contentLocale);
            reportRepository.save(report);
        }
        Resume resume = resumeRepository.findById(report.getResumeId())
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));
        return new AnalysisContext(
                resume.getId(),
                resume.getUserId(),
                resume.getExternalId(),
                resume.getRawText(),
                resume.getParsedContent(),
                contentLocale
        );
    }

    /**
     * Normalizes model output and computes bounded aggregate scores.
     *
     * @param scoring scoring payload
     * @return normalized analysis result
     * @throws Exception when scoring payload is invalid
     */
    private AnalysisResult computeAndExtractResult(ScoringResult scoring) throws Exception {
        if (scoring == null || scoring.scores() == null) {
            throw new IllegalStateException("Missing scores object");
        }

        Integer completeness = scoring.scores().completeness();
        Integer clarity = scoring.scores().clarity();

        if (completeness == null || clarity == null) {
            throw new IllegalStateException("Missing numeric score: completeness/clarity");
        }

        completeness = clampScore(completeness);
        clarity = clampScore(clarity);
        int overall = clampScore(computeOverallScore(completeness, clarity));

        String summary = normalizeToNull(scoring.summary());
        String improvementSuggestionsJson = canonicalizeArrayOrEmpty(scoring.improvementSuggestions());
        String sectionAnalysisJson = canonicalizeArrayOrEmpty(scoring.sectionAnalysis());

        return new AnalysisResult(
                completeness,
                clarity,
                overall,
                summary,
                improvementSuggestionsJson,
                sectionAnalysisJson
        );
    }

    /**
     * Persists successful analysis values and updates resume analysis status.
     *
     * @param reportId report id
     * @param resumeId resume id
     * @param evidenceJson stage A evidence json
     * @param result normalized result
     * @param modelName stage B model name
     */
    private void persistSuccess(Long reportId, Long resumeId, String evidenceJson, AnalysisResult result, String modelName) {
        ResumeAnalysisReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException("RESUME_010", "Resume analysis report not found"));
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        report.setCompletenessScore(result.completenessScore());
        report.setClarityScore(result.clarityScore());
        report.setOverallScore(result.overallScore());
        report.setEvidenceJson(evidenceJson);
        report.setSummary(result.summary());
        report.setImprovementSuggestionsJson(result.improvementSuggestionsJson());
        report.setSectionAnalysisJson(result.sectionAnalysisJson());
        report.setStatus(AnalysisStatus.COMPLETED);
        report.setCompletedAt(LocalDateTime.now());
        report.setErrorMessage(null);
        report.setPromptVersion(ResumeAnalysisPrompts.FRAMEWORK_VERSION);
        report.setModelName(modelName);
        reportRepository.save(report);

        resume.setAnalysisStatus(AnalysisStatus.COMPLETED);
        resume.setErrorMessage(null);
        resumeRepository.save(resume);
    }

    /**
     * Clears score/report fields before rerunning analysis.
     *
     * @param report report to reset
     */
    private static void resetReportForRerun(ResumeAnalysisReport report) {
        report.setCompletenessScore(0);
        report.setClarityScore(0);
        report.setOverallScore(0);
        report.setEvidenceJson(null);
        report.setSummary(null);
        report.setImprovementSuggestionsJson(null);
        report.setSectionAnalysisJson(null);
        report.setErrorMessage(null);
        report.setCompletedAt(null);
        report.setRetryCount(0);
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

    private String resolveUserContentLocale(Long userId) {
        return userRepository.findById(userId)
                .map(user -> ContentLocale.normalizeOrDefault(user.getLocale()))
                .orElse(ContentLocale.DEFAULT.getTag());
    }

    /**
     * Ensures LLM output is a JSON object before deserialization.
     *
     * @param json json string
     * @param context validation context
     * @throws Exception when the payload is not a json object
     */
    private void enforceJsonObject(String json, String context) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject()) {
            throw new IllegalStateException(context + " must be a JSON object");
        }
    }

    /**
     * Computes weighted overall score for pure resume analysis.
     *
     * @param completeness completeness score
     * @param clarity clarity score
     * @return rounded overall score
     */
    private static int computeOverallScore(int completeness, int clarity) {
        BigDecimal completenessBd = BigDecimal.valueOf(completeness);
        BigDecimal clarityBd = BigDecimal.valueOf(clarity);

        BigDecimal overall = completenessBd.multiply(new BigDecimal("0.5"))
                .add(clarityBd.multiply(new BigDecimal("0.5")));

        return overall.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * Clamps a score to the supported 0-100 range.
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
     * Serializes array node; returns empty array JSON for null/non-array inputs.
     *
     * @param node source node
     * @return json array string
     * @throws Exception when serialization fails
     */
    private String canonicalizeArrayOrEmpty(JsonNode node) throws Exception {
        if (node == null || node.isNull()) {
            return "[]";
        }
        if (!node.isArray()) {
            return "[]";
        }
        return objectMapper.writeValueAsString(node);
    }

    /**
     * Reads either a JSON array or a single scalar into a string list.
     *
     * @param node source JSON node
     * @return normalized string list
     */
    private static List<String> readStringArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        if (!node.isArray()) {
            String text = asTextOrNull(node);
            return text == null ? Collections.emptyList() : List.of(text);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = asTextOrNull(item);
            if (text != null) {
                values.add(text);
            }
        }
        return values;
    }

    /**
     * Extracts a non-blank text value from a JSON node.
     *
     * @param node source JSON node
     * @return text value or {@code null}
     */
    private static String asTextOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * Produces a bounded error message that is safe to persist and expose.
     *
     * @param exception source exception
     * @return safe bounded message
     */
    public static String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "Resume analysis failed";
        }
        message = message.trim();
        if (message.length() > ERROR_MESSAGE_MAX_LENGTH) {
            message = message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        }
        return message;
    }

    /**
     * Maps execution failures into retryable or terminal analysis exceptions.
     *
     * @param exception original exception
     * @return normalized execution exception
     */
    private ResumeAnalysisExecutionException classifyExecutionException(Exception exception) {
        String message = safeErrorMessage(exception);
        if (exception instanceof ResumeAnalysisExecutionException executionException) {
            return executionException;
        }
        if (exception instanceof LlmException llmException) {
            return new ResumeAnalysisExecutionException(message, llmException.isRetryable(), exception);
        }
        if (exception instanceof BusinessException businessException) {
            boolean retryable = "LLM_001".equals(businessException.getErrorCode());
            return new ResumeAnalysisExecutionException(message, retryable, exception);
        }
        return new ResumeAnalysisExecutionException(message, false, exception);
    }

    /**
     * Normalized analysis execution failure carrying retryability.
     */
    public static class ResumeAnalysisExecutionException extends RuntimeException {

        private final boolean retryable;

        /**
         * Creates an execution exception.
         *
         * @param message   safe error message
         * @param retryable whether the failure can be retried
         */
        public ResumeAnalysisExecutionException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        /**
         * Creates an execution exception with the original cause attached.
         *
         * @param message   safe error message
         * @param retryable whether the failure can be retried
         * @param cause     original cause
         */
        public ResumeAnalysisExecutionException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        /**
         * Returns whether the failure can be retried safely.
         *
         * @return retryable flag
         */
        public boolean isRetryable() {
            return retryable;
        }
    }

    private record AnalysisContext(
            Long resumeId,
            Long userId,
            UUID resumeExternalId,
            String rawText,
            String parsedContent,
            String contentLocale
    ) {
    }

    private record AnalysisResult(
            int completenessScore,
            int clarityScore,
            int overallScore,
            String summary,
            String improvementSuggestionsJson,
            String sectionAnalysisJson
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResumeEvidence(
            JsonNode personalInfo,
            JsonNode education,
            JsonNode workExperience,
            JsonNode skills,
            JsonNode projects,
            JsonNode evidenceQuality
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScoringResult(
            Scores scores,
            JsonNode sectionAnalysis,
            JsonNode improvementSuggestions,
            String summary
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Scores(
                Integer completeness,
                Integer clarity,
                Integer overall
        ) {
        }
    }

    /**
     * Validates that evidence JSON contains all required top-level keys.
     *
     * @param evidenceJson evidence JSON to validate
     * @throws Exception when the evidence schema is invalid
     */
    private void enforceEvidenceSchema(String evidenceJson) throws Exception {
        JsonNode root = objectMapper.readTree(evidenceJson);
        if (!root.isObject()) {
            throw new IllegalStateException("Evidence must be a JSON object");
        }

        for (String key : EVIDENCE_REQUIRED_TOP_LEVEL_KEYS) {
            if (!root.has(key) || root.get(key).isNull()) {
                throw new IllegalStateException("Evidence is missing required key: " + key);
            }
        }
    }
}
