package com.josh.interviewj.interview.service;

import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationRubricPayload;
import com.josh.interviewj.interview.llm.prompt.InterviewEvaluationPrompts;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewHeuristicEvaluationFallback;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.usage.model.UsageFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for evaluating interview answers.
 *
 * <p>Supports LLM rubric evaluation with heuristic fallback.
 * When LLM is unavailable or fails, falls back to deterministic scoring.</p>
 */
@Service
public class InterviewEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationService.class);
    private static final String PURPOSE_EVALUATION = "interview_answer_evaluation";
    private static final String RESOURCE_TYPE_INTERVIEW_SESSION = "INTERVIEW_SESSION";

    private final AiOperationGateway aiOperationGateway;
    private final InterviewLlmJsonParser jsonParser;
    private final InterviewHeuristicEvaluationFallback heuristicFallback;
    private final ObjectMapper objectMapper;
    private final InterviewSessionRepository interviewSessionRepository;

    @Autowired
    public InterviewEvaluationService(
            AiOperationGateway aiOperationGateway,
            InterviewLlmJsonParser jsonParser,
            InterviewSessionRepository interviewSessionRepository) {
        this.aiOperationGateway = aiOperationGateway;
        this.jsonParser = jsonParser;
        this.heuristicFallback = new InterviewHeuristicEvaluationFallback();
        this.objectMapper = JsonMapper.builder().build();
        this.interviewSessionRepository = interviewSessionRepository;
    }

    InterviewEvaluationService(
            AiOperationGateway aiOperationGateway,
            InterviewLlmJsonParser jsonParser) {
        this(aiOperationGateway, jsonParser, null);
    }

    /**
     * Evaluate an interview answer.
     *
     * <p>Uses LLM rubric evaluation when available, with heuristic fallback.</p>
     *
     * @param question the interview question
     * @param answerContent the answer content
     * @param durationSeconds answer duration in seconds
     * @param jobTitle job title for context
     * @param contentLocale content locale for language
     * @return evaluation envelope with scores and comments
     */
    public InterviewEvaluationEnvelope evaluateAnswer(
            InterviewQuestion question,
            String answerContent,
            Integer durationSeconds,
            String jobTitle,
            String contentLocale
    ) {
        ContentLocale locale = ContentLocale.resolveOrDefault(contentLocale);
        LlmEvaluationResult llmResult = null;

        // Try LLM rubric evaluation
        try {
            llmResult = tryLlmEvaluation(
                    question,
                    question.getQuestionContent(),
                    answerContent,
                    jobTitle,
                    locale
            );
            if (llmResult.envelope() != null) {
                log.debug("LLM evaluation succeeded for question {}", question.getId());
                submitOutcome(llmResult, InvocationUsageOutcome.SUCCESS, null);
                return llmResult.envelope();
            }
        } catch (BusinessException e) {
            if (e instanceof LlmEvaluationAttemptException attemptException) {
                llmResult = attemptException.llmResult();
                e = attemptException.originalException();
            }
            if (isCircuitBreakerOpen(e)) {
                log.info("Circuit breaker open for evaluation, using heuristic fallback");
            } else {
                log.warn("LLM evaluation failed, using heuristic fallback: {}", e.getMessage());
            }
            submitOutcome(llmResult, InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE, e.getMessage());
        } catch (Exception e) {
            if (e instanceof LlmEvaluationAttemptException attemptException) {
                llmResult = attemptException.llmResult();
                e = attemptException.originalException();
            }
            log.warn("LLM evaluation failed with unexpected error, using heuristic fallback: {}", e.getMessage());
            submitOutcome(llmResult, InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE, e.getMessage());
        }

        // Fallback to heuristic evaluation
        return heuristicFallback.evaluate(
                question.getQuestionContent(),
                answerContent,
                durationSeconds,
                jobTitle
        );
    }

    /**
     * Evaluate an interview answer (backward-compatible overload).
     *
     * @param question the interview question
     * @param answerContent the answer content
     * @param durationSeconds answer duration in seconds
     * @return evaluation result with score, summary, and details JSON
     */
    public InterviewEvaluationResult evaluateAnswer(
            InterviewQuestion question,
            String answerContent,
            Integer durationSeconds
    ) {
        InterviewEvaluationEnvelope envelope = evaluateAnswer(
                question,
                answerContent,
                durationSeconds,
                null,
                ContentLocale.DEFAULT.getTag()
        );

        BigDecimal score = calculateScoreFromEnvelope(envelope, answerContent, durationSeconds);
        String summary = envelope.overallComment();
        String detailsJson = serializeEnvelope(envelope);

        return new InterviewEvaluationResult(score, summary, detailsJson);
    }

    private LlmEvaluationResult tryLlmEvaluation(
            InterviewQuestion question,
            String questionContent,
            String answerContent,
            String jobTitle,
            ContentLocale locale
    ) {
        String systemPrompt = InterviewEvaluationPrompts.buildSystemPrompt(locale);
        String userPrompt = InterviewEvaluationPrompts.buildEvaluationUserPrompt(
                questionContent,
                answerContent,
                jobTitle != null ? jobTitle : "Software Engineer",
                locale
        );

        AtomicReference<InterviewEvaluationRubricPayload> rubricRef = new AtomicReference<>();
        InvocationEnvelope invocationEnvelope = createInvocationEnvelope(question);
        AiInvocationResult invocationResult = aiOperationGateway.executeInvocation(
                invocationEnvelope.operationContext(),
                invocationEnvelope.invocationContext(),
                AiInvocationInput.chat(systemPrompt, userPrompt, content -> rubricRef.set(jsonParser.parseEvaluationRubric(content)),
                        new PromptTemplateRef(
                                "interview_evaluation",
                                buildEvaluationTemplateVariables(questionContent, answerContent, jobTitle, locale)
                        ))
        );
        LlmResponse llmResponse = invocationResult.llmResponse();
        String json = llmResponse.content();
        try {
            InterviewEvaluationRubricPayload rubric = rubricRef.get();
            if (rubric == null) {
                rubric = jsonParser.parseEvaluationRubric(json);
            }
            return new LlmEvaluationResult(
                    InterviewEvaluationEnvelope.fromRubric(rubric),
                    invocationEnvelope.operationContext(),
                    invocationEnvelope.invocationContext(),
                    invocationResult
            );
        } catch (BusinessException exception) {
            throw new LlmEvaluationAttemptException(exception, new LlmEvaluationResult(
                    null,
                    invocationEnvelope.operationContext(),
                    invocationEnvelope.invocationContext(),
                    invocationResult
            ));
        } catch (Exception exception) {
            throw new LlmEvaluationAttemptException(
                    new BusinessException("LLM_003", exception.getMessage() == null ? "Interview evaluation parsing failed" : exception.getMessage(), exception),
                    new LlmEvaluationResult(
                            null,
                            invocationEnvelope.operationContext(),
                            invocationEnvelope.invocationContext(),
                            invocationResult
                    )
            );
        }
    }

    private boolean isCircuitBreakerOpen(BusinessException e) {
        return e.getErrorCode() != null && e.getErrorCode().equals("LLM_002");
    }

    private BigDecimal calculateScoreFromEnvelope(
            InterviewEvaluationEnvelope envelope,
            String answerContent,
            Integer durationSeconds
    ) {
        // If LLM rubric was used, calculate weighted score
        BigDecimal weightedScore = envelope.calculateWeightedScore();
        if (weightedScore != null) {
            return weightedScore;
        }

        // Otherwise use heuristic score
        return heuristicFallback.calculateScore(answerContent, durationSeconds);
    }

    private String serializeEnvelope(InterviewEvaluationEnvelope envelope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", envelope.schemaVersion());
        payload.put("mode", envelope.mode());
        payload.put("fallbackUsed", envelope.fallbackUsed());

        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("answerRelevance", envelope.rubric() == null ? null : envelope.rubric().answerRelevance());
        rubric.put("specificity", envelope.rubric() == null ? null : envelope.rubric().specificity());
        rubric.put("reasoning", envelope.rubric() == null ? null : envelope.rubric().reasoning());
        rubric.put("technicalJudgment", envelope.rubric() == null ? null : envelope.rubric().technicalJudgment());
        rubric.put("communication", envelope.rubric() == null ? null : envelope.rubric().communication());
        payload.put("rubric", rubric);
        payload.put("overallComment", envelope.overallComment());
        payload.put("evidence", envelope.evidence() == null ? List.of() : envelope.evidence());
        payload.put("risks", envelope.risks() == null ? List.of() : envelope.risks());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize evaluation envelope", exception);
        }
    }

    /**
     * Result of interview answer evaluation.
     *
     * @param score the evaluation score (0-100)
     * @param summary a brief summary of the evaluation
     * @param evaluationDetailsJson JSON string containing detailed evaluation
     */
    public record InterviewEvaluationResult(
            BigDecimal score,
            String summary,
            String evaluationDetailsJson
    ) {
    }

    private InvocationEnvelope createInvocationEnvelope(InterviewQuestion question) {
        if (interviewSessionRepository == null || question == null) {
            throw new BusinessException("INTERVIEW_004", "Interview not found");
        }
        InterviewSession session = interviewSessionRepository.findById(question.getSessionId())
                .orElseThrow(() -> new BusinessException("INTERVIEW_004", "Interview not found"));
        String businessOperationId = "interview-evaluation-" + question.getExternalId() + "-" + UUID.randomUUID();
        return new InvocationEnvelope(
                aiOperationGateway.prepareOperation(new BusinessOperationContext(
                        businessOperationId,
                        session.getUserId(),
                        RESOURCE_TYPE_INTERVIEW_SESSION,
                        session.getExternalId().toString(),
                        PURPOSE_EVALUATION,
                        List.of("INTERVIEW_CREDITS"),
                        Map.of()
                )),
                new AiInvocationContext(
                        businessOperationId + ":chat",
                        PURPOSE_EVALUATION,
                        UsageFamily.CHAT,
                        "INTERVIEW_CREDITS",
                        true,
                        Map.of()
                )
        );
    }

    private void submitOutcome(
            LlmEvaluationResult llmResult,
            InvocationUsageOutcome outcome,
            String failureReason
    ) {
        if (llmResult == null || llmResult.invocationResult() == null) {
            return;
        }
        aiOperationGateway.submitInvocationOutcome(
                llmResult.operationContext(),
                llmResult.invocationContext(),
                llmResult.invocationResult(),
                ExecutionDisposition.EXECUTED,
                outcome,
                failureReason
        );
    }

    private record LlmEvaluationResult(
            InterviewEvaluationEnvelope envelope,
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }

    private record InvocationEnvelope(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext
    ) {
    }

    private static class LlmEvaluationAttemptException extends BusinessException {

        private final LlmEvaluationResult llmResult;
        private final BusinessException originalException;

        private LlmEvaluationAttemptException(BusinessException originalException, LlmEvaluationResult llmResult) {
            super(originalException.getErrorCode(), originalException.getMessage(), originalException);
            this.llmResult = llmResult;
            this.originalException = originalException;
        }

        private LlmEvaluationResult llmResult() {
            return llmResult;
        }

        private BusinessException originalException() {
            return originalException;
        }
    }

    private Map<String, Object> buildEvaluationTemplateVariables(
            String questionContent,
            String answerContent,
            String jobTitle,
            ContentLocale locale
    ) {
        return Map.of(
                "promptLanguage", locale.getPromptLanguage(),
                "jobTitle", defaultJobTitle(jobTitle),
                "question", questionContent,
                "answer", truncate(answerContent, 3000)
        );
    }

    private String defaultJobTitle(String jobTitle) {
        return jobTitle != null ? jobTitle : "Software Engineer";
    }

    /**
     * Truncate text to specified length to match fallback path behavior.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
