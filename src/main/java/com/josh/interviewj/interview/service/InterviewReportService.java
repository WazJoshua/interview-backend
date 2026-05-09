package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.response.InterviewReportResponse;
import com.josh.interviewj.interview.llm.dto.InterviewReportPayload;
import com.josh.interviewj.interview.llm.prompt.InterviewReportPrompts;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewOverallScoreCalculator;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.josh.interviewj.usage.model.UsageFamily;

@Service
@Slf4j
public class InterviewReportService {

    private static final String PURPOSE_REPORT_GENERATION = "interview_report_generation";
    private static final String RESOURCE_TYPE_INTERVIEW_REPORT = "INTERVIEW_REPORT";

    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final AiOperationGateway aiOperationGateway;
    private final InterviewLlmJsonParser jsonParser;
    private final InterviewOverallScoreCalculator overallScoreCalculator;
    private final InterviewScoringService interviewScoringService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    InterviewReportService(
            UserRepository userRepository,
            InterviewSessionRepository interviewSessionRepository,
            InterviewReportRepository interviewReportRepository,
            InterviewQuestionRepository interviewQuestionRepository,
            InterviewAnswerRepository interviewAnswerRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            ObjectMapper objectMapper,
            AiOperationGateway aiOperationGateway,
            InterviewLlmJsonParser jsonParser,
            InterviewOverallScoreCalculator overallScoreCalculator,
            InterviewScoringService interviewScoringService,
            TransactionTemplate transactionTemplate
    ) {
        this.userRepository = userRepository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewReportRepository = interviewReportRepository;
        this.interviewQuestionRepository = interviewQuestionRepository;
        this.interviewAnswerRepository = interviewAnswerRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.aiOperationGateway = aiOperationGateway;
        this.jsonParser = jsonParser;
        this.overallScoreCalculator = overallScoreCalculator;
        this.interviewScoringService = interviewScoringService;
        this.transactionTemplate = transactionTemplate;
    }

    @Autowired(required = false)
    private ChatEventRecorder chatEventRecorder;

    @Autowired(required = false)
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Autowired(required = false)
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    @Transactional(readOnly = true)
    public InterviewReportResponse getReport(String username, UUID interviewId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        return toResponse(session, interviewReportRepository.findBySessionId(session.getId()).orElse(null));
    }

    @Transactional
    public InterviewReport prepareReportGeneration(InterviewSession session) {
        InterviewReport report = interviewReportRepository.findBySessionId(session.getId())
                .orElseGet(() -> InterviewReport.builder()
                        .sessionId(session.getId())
                        .externalId(UUID.randomUUID())
                        .build());
        report.setStatus(InterviewReportStatus.GENERATING);
        report.setFailureCode(null);
        report.setFailureMessage(null);
        report.setFailedAt(null);
        return interviewReportRepository.save(report);
    }

    @Transactional
    public boolean retryFailedReportGeneration(InterviewSession session) {
        Optional<InterviewReport> reportOptional = interviewReportRepository.findBySessionId(session.getId());
        if (reportOptional.isEmpty()) {
            return false;
        }
        InterviewReport report = reportOptional.get();
        if (report.getStatus() != InterviewReportStatus.FAILED) {
            return false;
        }
        report.setStatus(InterviewReportStatus.GENERATING);
        report.setFailureCode(null);
        report.setFailureMessage(null);
        report.setFailedAt(null);
        interviewReportRepository.save(report);
        return true;
    }

    public void generateReport(Long sessionId) {
        InterviewSession session = interviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        InterviewReport report = interviewReportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Report not found"));
        if (report.getStatus() != InterviewReportStatus.GENERATING) {
            return;
        }

        LlmReportGenerationResult generationResult = null;
        try {
            List<InterviewQuestion> questions = interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
            List<InterviewAnswer> answers = interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            BigDecimal overallScore = calculateOverallScore(session, questions, answers);
            generationResult = generateReportContent(session, report, questions, answers, overallScore);
            InterviewReportPayload payload = generationResult.payload();
            transactionTemplate.execute(status -> {
                persistGeneratedReport(sessionId, overallScore, payload);
                return null;
            });
            submitOutcome(generationResult, InvocationUsageOutcome.SUCCESS, null);
        } catch (Exception exception) {
            log.warn("event=interview_report_generation_failed session_id={} reason={}", sessionId, exception.getMessage());
            submitOutcome(generationResult, InvocationUsageOutcome.FAILED_NON_CHARGEABLE, safeFailureMessage(exception));
            transactionTemplate.execute(status -> {
                markReportFailed(sessionId);
                return null;
            });
        }
    }

    @Transactional
    protected void persistGeneratedReport(Long sessionId, BigDecimal overallScore, InterviewReportPayload payload) {
        InterviewSession session = interviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        InterviewReport report = interviewReportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Report not found"));
        if (report.getStatus() != InterviewReportStatus.GENERATING) {
            return;
        }

        SummaryMessageContext summaryMessageContext = null;
        try {
            report.setOverallScore(overallScore);
            report.setContentQualityScore(null);
            report.setExpressionQualityScore(null);
            report.setLogicQualityScore(null);
            report.setSummary(payload.summary());
            report.setStrengths(payload.strengths().toArray(new String[0]));
            report.setWeaknesses(payload.weaknesses().toArray(new String[0]));
            report.setImprovementSuggestions(serializeSuggestions(payload.improvementSuggestions()));
            report.setSkillAssessment(serializeSkillAssessment(payload.skillAssessment()));
            report.setGeneratedAt(LocalDateTime.now());
            report.setStatus(InterviewReportStatus.READY);
            report.setFailureCode(null);
            report.setFailureMessage(null);
            report.setFailedAt(null);
            interviewReportRepository.save(report);

            summaryMessageContext = persistSummaryMessage(session, report, overallScore);
            emitReadyEvent(session, report);
        } catch (Exception exception) {
            rollbackSummaryMessage(summaryMessageContext, report);
            prepareFailedReport(report);
            interviewReportRepository.save(report);
            emitFailureEvent(session, report);
            throw exception;
        }
    }

    @Transactional
    protected void markReportFailed(Long sessionId) {
        InterviewSession session = interviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        InterviewReport report = interviewReportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Report not found"));
        if (report.getStatus() != InterviewReportStatus.GENERATING) {
            return;
        }
        prepareFailedReport(report);
        interviewReportRepository.save(report);
        emitFailureEvent(session, report);
    }

    private BigDecimal calculateOverallScore(
            InterviewSession session,
            List<InterviewQuestion> questions,
            List<InterviewAnswer> answers
    ) {
        if (session.getRunningScore() == null) {
            return null;
        }

        String completionReason = session.getCompletionReason() != null
                ? session.getCompletionReason().name()
                : null;

        // Collect branch scores for instability penalty calculation
        List<BigDecimal> branchScores = collectBranchScores(questions, answers);

        // Calculate overall score with all three penalties
        InterviewOverallScoreCalculator.OverallScoreResult result = overallScoreCalculator.calculateWithPenalties(
                session.getRunningScore(),
                completionReason,
                session.getAnsweredMainQuestionCount() != null ? session.getAnsweredMainQuestionCount() : 0,
                session.getMainQuestionCount() != null ? session.getMainQuestionCount() : 0,
                branchScores
        );

        log.debug("Overall score calculation: runningScore={}, earlyEndPenalty={}, incompletionPenalty={}, instabilityPenalty={}, finalScore={}",
                session.getRunningScore(),
                result.earlyEndPenalty(),
                result.incompletionPenalty(),
                result.instabilityPenalty(),
                result.overallScore());

        return result.overallScore();
    }

    /**
     * Collect finalized branch scores for instability penalty calculation.
     * Each branch score represents the final score for a main question branch
     * (including any follow-up questions).
     */
    private List<BigDecimal> collectBranchScores(List<InterviewQuestion> questions, List<InterviewAnswer> answers) {
        if (questions == null || answers == null) {
            return List.of();
        }

        Map<Long, InterviewQuestion> questionsById = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()));
        Map<Long, InterviewAnswer> answersByQuestionId = answers.stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, Function.identity(), (left, right) -> left));
        List<InterviewQuestion> mainQuestions = questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.MAIN)
                .sorted(Comparator.comparingInt(InterviewQuestion::getSequenceNumber))
                .toList();

        List<BigDecimal> branchScores = new java.util.ArrayList<>();
        for (InterviewQuestion mainQuestion : mainQuestions) {
            InterviewAnswer mainAnswer = answersByQuestionId.get(mainQuestion.getId());
            if (mainAnswer == null || mainAnswer.getEvaluationScore() == null) {
                continue;
            }

            List<InterviewQuestion> branchQuestions = questions.stream()
                    .filter(q -> q.getQuestionKind() == InterviewQuestionKind.FOLLOW_UP)
                    .filter(q -> belongsToRoot(q, mainQuestion.getId(), questionsById))
                    .sorted(Comparator.comparingInt(InterviewQuestion::getBranchDepth))
                    .toList();
            boolean allAnswered = branchQuestions.stream().allMatch(q -> answersByQuestionId.containsKey(q.getId()));

            if (!allAnswered) {
                if (branchQuestions.isEmpty()) {
                    branchScores.add(interviewScoringService.finalizeMainOnly(mainAnswer.getEvaluationScore()));
                }
                continue;
            }
            if (branchQuestions.isEmpty()) {
                branchScores.add(interviewScoringService.finalizeMainOnly(mainAnswer.getEvaluationScore()));
                continue;
            }

            InterviewQuestion lastQuestion = branchQuestions.getLast();
            if (lastQuestion.getFollowUpIntent() == InterviewFollowUpIntent.CLARIFY) {
                branchScores.add(interviewScoringService.finalizeClarifyBranch(
                        mainAnswer.getEvaluationScore(),
                        answersByQuestionId.get(lastQuestion.getId()).getEvaluationScore()
                ));
                continue;
            }

            List<BigDecimal> deepDiveScores = branchQuestions.stream()
                    .map(q -> answersByQuestionId.get(q.getId()))
                    .filter(java.util.Objects::nonNull)
                    .map(InterviewAnswer::getEvaluationScore)
                    .toList();
            branchScores.add(interviewScoringService.finalizeDeepDiveBranch(
                    mainAnswer.getEvaluationScore(),
                    deepDiveScores
            ));
        }

        return branchScores;
    }

    private boolean belongsToRoot(
            InterviewQuestion question,
            Long rootMainQuestionId,
            Map<Long, InterviewQuestion> questionsById
    ) {
        InterviewQuestion current = question;
        while (current.getParentQuestionId() != null) {
            if (current.getParentQuestionId().equals(rootMainQuestionId)) {
                return true;
            }
            current = questionsById.get(current.getParentQuestionId());
            if (current == null) {
                return false;
            }
        }
        return false;
    }

    private LlmReportGenerationResult generateReportContent(
            InterviewSession session,
            InterviewReport report,
            List<InterviewQuestion> questions,
            List<InterviewAnswer> answers,
            BigDecimal overallScore
    ) {
        ContentLocale locale = ContentLocale.resolveOrDefault(session.getContentLocale());
        String questionsSummary = buildQuestionsSummary(questions);
        String answersSummary = buildAnswersSummary(answers);
        String branchSummary = buildBranchSummary(questions, answers);

        String systemPrompt = InterviewReportPrompts.buildSystemPrompt(locale);
        String userPrompt = InterviewReportPrompts.buildReportUserPrompt(
                session.getJobTitle(),
                session.getJobDescription(),
                questionsSummary,
                answersSummary,
                session.getRunningScore(),
                overallScore,
                session.getCompletionReason() == null ? null : session.getCompletionReason().name(),
                branchSummary,
                locale
        );

        AtomicReference<InterviewReportPayload> payloadRef = new AtomicReference<>();
        String businessOperationId = "interview-report-" + report.getExternalId() + "-" + UUID.randomUUID();
        BusinessOperationContext operationContext = aiOperationGateway.prepareOperation(new BusinessOperationContext(
                businessOperationId,
                session.getUserId(),
                RESOURCE_TYPE_INTERVIEW_REPORT,
                report.getExternalId().toString(),
                PURPOSE_REPORT_GENERATION,
                List.of("INTERVIEW_CREDITS"),
                Map.of()
        ));
        AiInvocationContext invocationContext = new AiInvocationContext(
                businessOperationId + ":chat",
                PURPOSE_REPORT_GENERATION,
                UsageFamily.CHAT,
                "INTERVIEW_CREDITS",
                false,
                Map.of()
        );
        AiInvocationResult invocationResult = aiOperationGateway.executeInvocation(
                operationContext,
                invocationContext,
                AiInvocationInput.chat(systemPrompt, userPrompt, content -> payloadRef.set(jsonParser.parseReport(content)),
                        new PromptTemplateRef(
                                "interview_report",
                                buildReportTemplateVariables(session, locale, questionsSummary, answersSummary, branchSummary, overallScore)
                        ))
        );
        LlmResponse response = invocationResult.llmResponse();

        InterviewReportPayload payload = payloadRef.get() != null ? payloadRef.get() : jsonParser.parseReport(response.content());
        return new LlmReportGenerationResult(
                payload,
                operationContext,
                invocationContext,
                invocationResult
        );
    }

    private Map<String, Object> buildReportTemplateVariables(
            InterviewSession session,
            ContentLocale locale,
            String questionsSummary,
            String answersSummary,
            String branchSummary,
            BigDecimal overallScore
    ) {
        return Map.of(
                "promptLanguage", locale.getPromptLanguage(),
                "jobTitle", session.getJobTitle() != null ? session.getJobTitle() : "N/A",
                "jobDescription", truncate(session.getJobDescription(), 1000),
                "questionsSummary", truncate(questionsSummary, 3000),
                "answersSummary", truncate(answersSummary, 3000),
                "branchSummary", truncate(branchSummary, 3000),
                "runningScore", session.getRunningScore() != null ? session.getRunningScore().toString() : "N/A",
                "overallScore", overallScore != null ? overallScore.toString() : "N/A",
                "completionReason", session.getCompletionReason() != null ? session.getCompletionReason().name() : "N/A"
        );
    }

    private String buildQuestionsSummary(List<InterviewQuestion> questions) {
        return questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.MAIN)
                .map(q -> String.format("Q%d [%s]: %s",
                        q.getSequenceNumber(),
                        q.getQuestionType(),
                        q.getQuestionContent()))
                .collect(Collectors.joining("\n"));
    }

    private String buildAnswersSummary(List<InterviewAnswer> answers) {
        return answers.stream()
                .map(a -> String.format("Answer (score: %s): %s",
                        a.getEvaluationScore(),
                        truncate(a.getAnswerContent(), 200)))
                .collect(Collectors.joining("\n"));
    }

    private String buildBranchSummary(List<InterviewQuestion> questions, List<InterviewAnswer> answers) {
        if (questions == null || questions.isEmpty()) {
            return "";
        }
        Map<Long, InterviewQuestion> questionsById = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()));
        Map<Long, InterviewAnswer> answersByQuestionId = answers.stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, Function.identity(), (left, right) -> right));
        return questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.MAIN)
                .sorted(Comparator.comparingInt(InterviewQuestion::getSequenceNumber))
                .map(question -> buildSingleBranchSummary(question, questions, questionsById, answersByQuestionId))
                .filter(summary -> !summary.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String buildSingleBranchSummary(
            InterviewQuestion mainQuestion,
            List<InterviewQuestion> questions,
            Map<Long, InterviewQuestion> questionsById,
            Map<Long, InterviewAnswer> answersByQuestionId
    ) {
        InterviewAnswer mainAnswer = answersByQuestionId.get(mainQuestion.getId());
        if (mainAnswer == null) {
            return "";
        }
        List<InterviewQuestion> branchQuestions = questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.FOLLOW_UP)
                .filter(q -> belongsToRoot(q, mainQuestion.getId(), questionsById))
                .sorted(Comparator.comparingInt(InterviewQuestion::getBranchDepth))
                .toList();
        BigDecimal branchScore;
        if (branchQuestions.isEmpty()) {
            branchScore = interviewScoringService.finalizeMainOnly(mainAnswer.getEvaluationScore());
        } else {
            InterviewQuestion lastQuestion = branchQuestions.getLast();
            if (lastQuestion.getFollowUpIntent() == InterviewFollowUpIntent.CLARIFY) {
                InterviewAnswer clarifyAnswer = answersByQuestionId.get(lastQuestion.getId());
                if (clarifyAnswer == null) {
                    return "";
                }
                branchScore = interviewScoringService.finalizeClarifyBranch(
                        mainAnswer.getEvaluationScore(),
                        clarifyAnswer.getEvaluationScore()
                );
            } else {
                List<BigDecimal> deepDiveScores = branchQuestions.stream()
                        .map(q -> answersByQuestionId.get(q.getId()))
                        .filter(java.util.Objects::nonNull)
                        .map(InterviewAnswer::getEvaluationScore)
                        .toList();
                if (deepDiveScores.size() != branchQuestions.size()) {
                    return "";
                }
                branchScore = interviewScoringService.finalizeDeepDiveBranch(mainAnswer.getEvaluationScore(), deepDiveScores);
            }
        }
        return "MainQuestion#" + mainQuestion.getSequenceNumber()
                + " branchScore=" + branchScore
                + " evaluationSummary=" + extractEvaluationSummary(mainAnswer);
    }

    private String extractEvaluationSummary(InterviewAnswer answer) {
        if (answer.getEvaluationDetails() == null || answer.getEvaluationDetails().isBlank()) {
            return "";
        }
        try {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(answer.getEvaluationDetails());
            return root.path("overallComment").asText("");
        } catch (Exception exception) {
            return "";
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String serializeSuggestions(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(suggestions);
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeSkillAssessment(Map<String, InterviewReportPayload.SkillAssessment> assessment) {
        if (assessment == null || assessment.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(assessment);
        } catch (Exception e) {
            return null;
        }
    }

    private SummaryMessageContext persistSummaryMessage(InterviewSession session, InterviewReport report, BigDecimal overallScore) {
        ChatSession chatSession = chatSessionRepository.findByExternalId(session.getChatSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));
        UUID messageId = UUID.randomUUID();
        Integer originalNextMessageSequence = chatSession.getNextMessageSequence();
        Integer originalMessageCount = chatSession.getMessageCount();
        String originalLastMessagePreview = chatSession.getLastMessagePreview();
        LocalDateTime originalLastMessageAt = chatSession.getLastMessageAt();

        String summaryContent = report.getSummary() != null
                ? report.getSummary()
                : "Interview report ready. Overall score: " + (overallScore != null ? overallScore : "N/A");

        ChatMessage summaryMessage = chatMessageRepository.save(ChatMessage.builder()
                .externalId(messageId)
                .chatSessionId(chatSession.getId())
                .role(ChatRole.ASSISTANT)
                .messageType(ChatMessageType.INTERVIEW_REPORT_SUMMARY)
                .content(summaryContent)
                .metadata(serialize(Map.of(
                        "reportId", report.getExternalId(),
                        "reportStatus", InterviewReportStatus.READY.name(),
                        "overallScore", overallScore != null ? overallScore : "N/A"
                )))
                .sequenceNumber(chatSession.getNextMessageSequence())
                .estimatedTokenCount(summaryContent.length() / 4)
                .createdAt(LocalDateTime.now())
                .build());
        SummaryMessageContext context = new SummaryMessageContext(
                summaryMessage,
                chatSession,
                originalNextMessageSequence,
                originalMessageCount,
                originalLastMessagePreview,
                originalLastMessageAt
        );
        try {
            chatSession.setNextMessageSequence(chatSession.getNextMessageSequence() + 1);
            chatSession.setMessageCount(chatSession.getMessageCount() + 1);
            chatSession.setLastMessagePreview(summaryMessage.getContent());
            chatSession.setLastMessageAt(summaryMessage.getCreatedAt());
            chatSessionRepository.save(chatSession);
            report.setSummaryMessageId(summaryMessage.getExternalId());
            interviewReportRepository.save(report);
            return context;
        } catch (Exception exception) {
            rollbackSummaryMessage(context, report);
            throw exception;
        }
    }

    private void rollbackSummaryMessage(SummaryMessageContext context, InterviewReport report) {
        if (context == null) {
            return;
        }

        ChatSession chatSession = context.chatSession();
        chatSession.setNextMessageSequence(context.originalNextMessageSequence());
        chatSession.setMessageCount(context.originalMessageCount());
        chatSession.setLastMessagePreview(context.originalLastMessagePreview());
        chatSession.setLastMessageAt(context.originalLastMessageAt());
        chatSessionRepository.save(chatSession);
        chatMessageRepository.delete(context.summaryMessage());
        report.setSummaryMessageId(null);
    }

    private void prepareFailedReport(InterviewReport report) {
        report.setStatus(InterviewReportStatus.FAILED);
        report.setFailureCode("REPORT_GENERATION_FAILED");
        report.setFailureMessage("Report generation failed. Recovery may retry from FAILED to GENERATING.");
        report.setFailedAt(LocalDateTime.now());
        report.setOverallScore(null);
        report.setContentQualityScore(null);
        report.setExpressionQualityScore(null);
        report.setLogicQualityScore(null);
        report.setSummary(null);
        report.setStrengths(null);
        report.setWeaknesses(null);
        report.setImprovementSuggestions(null);
        report.setSkillAssessment(null);
        report.setGeneratedAt(null);
        report.setSummaryMessageId(null);
    }

    private InterviewReportResponse toResponse(InterviewSession session, InterviewReport report) {
        if (report == null) {
            return new InterviewReportResponse(
                    null,
                    session.getExternalId(),
                    InterviewReportStatus.NOT_READY.name(),
                    session.getCompletionReason() == null ? null : session.getCompletionReason().name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        // For FAILED status, don't expose scores
        boolean isFailed = report.getStatus() == InterviewReportStatus.FAILED;
        BigDecimal overallScore = isFailed ? null : report.getOverallScore();
        BigDecimal runningScore = isFailed ? null : session.getRunningScore();

        return new InterviewReportResponse(
                report.getExternalId(),
                session.getExternalId(),
                report.getStatus().name(),
                session.getCompletionReason() == null ? null : session.getCompletionReason().name(),
                overallScore,
                runningScore,
                // Quality scores are now always null as per design
                null,
                null,
                null,
                report.getStrengths() == null ? null : List.of(report.getStrengths()),
                report.getWeaknesses() == null ? null : List.of(report.getWeaknesses()),
                parseSuggestions(report.getImprovementSuggestions()),
                report.getStatus() == InterviewReportStatus.READY ? report.getGeneratedAt() : null,
                report.getFailureCode(),
                report.getFailureMessage(),
                isFailed,
                report.getFailedAt(),
                report.getSummary()
        );
    }

    private List<String> parseSuggestions(String suggestionsJson) {
        if (suggestionsJson == null || suggestionsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(suggestionsJson, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String serialize(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(metadata));
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void submitOutcome(
            LlmReportGenerationResult result,
            InvocationUsageOutcome outcome,
            String failureReason
    ) {
        if (result == null || result.invocationResult() == null) {
            return;
        }
        aiOperationGateway.submitInvocationOutcome(
                result.operationContext(),
                result.invocationContext(),
                result.invocationResult(),
                ExecutionDisposition.EXECUTED,
                outcome,
                failureReason
        );
    }

    private String safeFailureMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Interview report generation failed";
        }
        return message;
    }

    private void emitReadyEvent(InterviewSession session, InterviewReport report) {
        if (chatEventRecorder != null) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSessionRepository.findByExternalId(session.getChatSessionId()).map(ChatSession::getId).orElse(null),
                    null,
                    ChatDomainType.INTERVIEW,
                    "REPORT_READY",
                    "SUCCESS",
                    Map.of("reportId", report.getExternalId(), "reportStatus", report.getStatus().name())
            ));
        }
        if (interviewWebSocketEventPublisher != null && interviewWebSocketPayloadFactory != null) {
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "REPORT_READY",
                    report.getSummaryMessageId(),
                    Map.of("reportId", report.getExternalId(), "reportStatus", report.getStatus().name())
            ));
        }
    }

    private void emitFailureEvent(InterviewSession session, InterviewReport report) {
        if (chatEventRecorder != null) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSessionRepository.findByExternalId(session.getChatSessionId()).map(ChatSession::getId).orElse(null),
                    null,
                    ChatDomainType.INTERVIEW,
                    "ERROR",
                    "FAILED",
                    Map.of(
                            "reportId", report.getExternalId(),
                            "failureCode", report.getFailureCode(),
                            "failureMessage", report.getFailureMessage()
                    )
            ));
        }
        if (interviewWebSocketEventPublisher != null && interviewWebSocketPayloadFactory != null) {
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "ERROR",
                    null,
                    Map.of(
                            "reportId", report.getExternalId(),
                            "failureCode", report.getFailureCode(),
                            "failureMessage", report.getFailureMessage()
                    )
            ));
        }
    }

    private record SummaryMessageContext(
            ChatMessage summaryMessage,
            ChatSession chatSession,
            Integer originalNextMessageSequence,
            Integer originalMessageCount,
            String originalLastMessagePreview,
            LocalDateTime originalLastMessageAt
    ) {
    }

    private record LlmReportGenerationResult(
            InterviewReportPayload payload,
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }
}
