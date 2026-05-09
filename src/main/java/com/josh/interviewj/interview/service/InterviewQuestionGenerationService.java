package com.josh.interviewj.interview.service;

import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.llm.dto.InterviewGeneratedQuestionPayload;
import com.josh.interviewj.interview.llm.prompt.InterviewQuestionGenerationPrompts;
import com.josh.interviewj.interview.llm.support.InterviewLlmJsonParser;
import com.josh.interviewj.interview.config.InterviewQuestionGenerationProperties;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.support.InterviewDeterministicQuestionBuilder;
import com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory;
import com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory.QuestionBlueprint;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.usage.model.UsageFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for generating interview questions.
 *
 * <p>Supports LLM-powered generation with deterministic fallback.
 * Questions are generated based on blueprints that define structure and goals.</p>
 */
@Service
public class InterviewQuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionGenerationService.class);
    private static final String PURPOSE_QUESTION_GENERATION = "interview_question_generation";
    private static final String PURPOSE_FOLLOW_UP_GENERATION = "interview_follow_up_generation";
    private static final String RESOURCE_TYPE_INTERVIEW_SESSION = "INTERVIEW_SESSION";

    private final AiOperationGateway aiOperationGateway;
    private final InterviewQuestionBlueprintFactory blueprintFactory;
    private final InterviewDeterministicQuestionBuilder deterministicBuilder;
    private final InterviewLlmJsonParser jsonParser;
    private final InterviewQuestionGenerationProperties properties;

    public InterviewQuestionGenerationService(
            AiOperationGateway aiOperationGateway,
            InterviewQuestionBlueprintFactory blueprintFactory,
            InterviewDeterministicQuestionBuilder deterministicBuilder,
            InterviewLlmJsonParser jsonParser,
            InterviewQuestionGenerationProperties properties) {
        this.aiOperationGateway = aiOperationGateway;
        this.blueprintFactory = blueprintFactory;
        this.deterministicBuilder = deterministicBuilder;
        this.jsonParser = jsonParser;
        this.properties = properties;
    }

    /**
     * Generate main questions for an interview session.
     *
     * @param session interview session
     * @param resume optional resume (may be null)
     * @param contentLocale content locale for language
     * @return list of generated questions
     */
    public List<InterviewQuestion> generateMainQuestions(
            InterviewSession session,
            Resume resume,
            String contentLocale
    ) {
        return generateMainQuestions(session, resume, contentLocale, false);
    }

    /**
     * Generate main questions for an interview session (overload for backward compatibility).
     *
     * @param session interview session
     * @return list of generated questions
     */
    public List<InterviewQuestion> generateMainQuestions(InterviewSession session) {
        return generateMainQuestions(session, null, ContentLocale.DEFAULT.getTag(), false);
    }

    /**
     * Generate main questions with explicit fallback control.
     *
     * @param session interview session
     * @param resume optional resume (may be null)
     * @param contentLocale content locale for language
     * @param forceFallback if true, skip LLM and use deterministic fallback
     * @return list of generated questions
     */
    public List<InterviewQuestion> generateMainQuestions(
            InterviewSession session,
            Resume resume,
            String contentLocale,
            boolean forceFallback
    ) {
        // If resume is provided but not parsed, this is an error
        if (resume != null && resume.getStatus() != ResumeStatus.PARSED) {
            throw new BusinessException("INTERVIEW_008",
                    "Resume must be in PARSED status before starting interview");
        }

        // Get blueprints for the difficulty level
        List<QuestionBlueprint> blueprints = blueprintFactory.createBlueprints(session.getDifficultyLevel());
        ContentLocale locale = ContentLocale.resolveOrDefault(contentLocale);

        if (forceFallback) {
            log.info("Using deterministic fallback for question generation: sessionId={}, resumeProvided={}",
                    session.getId(), resume != null);
            return deterministicBuilder.buildQuestions(
                    session.getId(),
                    session.getJobTitle(),
                    session.getJobDescription(),
                    session.getDifficultyLevel(),
                    locale.getTag()
            );
        }

        LlmQuestionGenerationResult llmGenerationResult = null;
        try {
            llmGenerationResult = tryLlmGeneration(session, resume, locale, blueprints);
            List<InterviewQuestion> llmQuestions = llmGenerationResult.questions();
            if (llmQuestions != null && !llmQuestions.isEmpty()) {
                log.info("LLM question generation succeeded: sessionId={}, questionCount={}",
                        session.getId(), llmQuestions.size());
                submitOutcome(llmGenerationResult, InvocationUsageOutcome.SUCCESS, null);
                return llmQuestions;
            }
        } catch (Exception e) {
            Exception failure = e;
            if (e instanceof QuestionGenerationAttemptException attemptException) {
                llmGenerationResult = attemptException.result();
                failure = attemptException.originalException();
            }
            log.warn("LLM question generation failed: sessionId={}, error={}", session.getId(), failure.getMessage());
            if (resume != null || !properties.isFallbackEnabled()) {
                submitOutcome(llmGenerationResult, InvocationUsageOutcome.FAILED_NON_CHARGEABLE, failure.getMessage());
            } else {
                submitOutcome(llmGenerationResult, InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE, failure.getMessage());
            }
        }

        if (resume != null) {
            throw new BusinessException("INTERVIEW_009",
                    "Question generation failed for resume-based interview. Please try again or start without resume.");
        }

        if (!properties.isFallbackEnabled()) {
            throw new BusinessException("INTERVIEW_010",
                    "Question generation failed. LLM service unavailable and fallback is disabled.");
        }

        return deterministicBuilder.buildQuestions(
                session.getId(),
                session.getJobTitle(),
                session.getJobDescription(),
                session.getDifficultyLevel(),
                locale.getTag()
        );
    }

    private LlmQuestionGenerationResult tryLlmGeneration(
            InterviewSession session,
            Resume resume,
            ContentLocale locale,
            List<QuestionBlueprint> blueprints
    ) {
        String systemPrompt = InterviewQuestionGenerationPrompts.buildSystemPrompt(locale);
        String userPrompt = InterviewQuestionGenerationPrompts.buildMainQuestionUserPrompt(
                session.getJobTitle(),
                session.getJobDescription(),
                resume == null ? null : resume.getParsedContent(),
                blueprints,
                locale
        );

        AtomicReference<List<InterviewGeneratedQuestionPayload>> payloadsRef = new AtomicReference<>();
        InvocationEnvelope invocationEnvelope = createInvocationEnvelope(
                session.getUserId(),
                session.getExternalId().toString(),
                PURPOSE_QUESTION_GENERATION
        );

        AiInvocationResult invocationResult = aiOperationGateway.executeInvocation(
                invocationEnvelope.operationContext(),
                invocationEnvelope.invocationContext(),
                AiInvocationInput.chat(systemPrompt, userPrompt, content -> payloadsRef.set(jsonParser.parseQuestions(content)),
                        new PromptTemplateRef(
                                "interview_question_generation_main",
                                buildMainQuestionTemplateVariables(session, resume, locale, blueprints)
                        ))
        );
        LlmResponse llmResponse = invocationResult.llmResponse();
        String json = llmResponse.content();

        List<InterviewGeneratedQuestionPayload> payloads = payloadsRef.get();
        try {
            if (payloads == null) {
                payloads = jsonParser.parseQuestions(json);
            }
            if (payloads.size() != blueprints.size()) {
                throw new BusinessException("LLM_003", "Generated question count does not match blueprint count");
            }

            Map<Integer, QuestionBlueprint> blueprintMap = blueprints.stream()
                    .collect(Collectors.toMap(QuestionBlueprint::sequenceNumber, Function.identity()));

            List<InterviewQuestion> questions = new ArrayList<>();
            for (InterviewGeneratedQuestionPayload payload : payloads) {
                int sequenceNumber = payload.sequenceNumber();
                QuestionBlueprint blueprint = blueprintMap.get(sequenceNumber);
                if (blueprint == null) {
                    throw new BusinessException("LLM_003", "Unknown sequenceNumber in generated questions: " + sequenceNumber);
                }

                questions.add(InterviewQuestion.builder()
                        .sessionId(session.getId())
                        .externalId(java.util.UUID.randomUUID())
                        .questionType(blueprint.questionType())
                        .questionContent(payload.questionContent())
                        .difficulty(blueprint.difficulty())
                        .estimatedMinutes(blueprint.estimatedMinutes())
                        .sequenceNumber(sequenceNumber)
                        .questionKind(InterviewQuestionKind.MAIN)
                        .branchDepth(0)
                        .build());
            }

            return new LlmQuestionGenerationResult(
                    questions.stream()
                            .sorted(java.util.Comparator.comparingInt(InterviewQuestion::getSequenceNumber))
                            .toList(),
                    invocationEnvelope.operationContext(),
                    invocationEnvelope.invocationContext(),
                    invocationResult
            );
        } catch (Exception exception) {
            throw new QuestionGenerationAttemptException(
                    exception,
                    new LlmQuestionGenerationResult(
                            List.of(),
                            invocationEnvelope.operationContext(),
                            invocationEnvelope.invocationContext(),
                            invocationResult
                    )
            );
        }
    }

    public String generateFollowUpQuestionContent(
            InterviewSession session,
            InterviewQuestion parentQuestion,
            String answerContent,
            InterviewFollowUpIntent followUpIntent,
            int branchDepth,
        String resumeContent
    ) {
        ContentLocale locale = ContentLocale.resolveOrDefault(session.getContentLocale());
        InvocationEnvelope invocationEnvelope = createInvocationEnvelope(
                session.getUserId(),
                session.getExternalId().toString(),
                PURPOSE_FOLLOW_UP_GENERATION
        );
        AiInvocationResult invocationResult = null;
        try {
            AtomicReference<String> questionContentRef = new AtomicReference<>();
            invocationResult = aiOperationGateway.executeInvocation(
                    invocationEnvelope.operationContext(),
                    invocationEnvelope.invocationContext(),
                    AiInvocationInput.chat(
                            InterviewQuestionGenerationPrompts.buildSystemPrompt(locale),
                            InterviewQuestionGenerationPrompts.buildFollowUpUserPrompt(
                                    parentQuestion.getQuestionContent(),
                                    answerContent,
                                    followUpIntent.name(),
                                    branchDepth,
                                    session.getJobTitle(),
                                    session.getJobDescription(),
                                    resumeContent,
                                    locale
                            ),
                            content -> questionContentRef.set(jsonParser.parseFollowUpQuestionContent(content)),
                            new PromptTemplateRef(
                                    "interview_question_generation_followup",
                                    buildFollowUpTemplateVariables(
                                            session,
                                            parentQuestion,
                                            answerContent,
                                            followUpIntent,
                                            branchDepth,
                                            resumeContent,
                                            locale
                                    )
                            )
                    )
            );
            LlmResponse llmResponse = invocationResult.llmResponse();
            String json = llmResponse.content();
            String questionContent = questionContentRef.get() != null
                    ? questionContentRef.get()
                    : jsonParser.parseFollowUpQuestionContent(json);
            aiOperationGateway.submitInvocationOutcome(
                    invocationEnvelope.operationContext(),
                    invocationEnvelope.invocationContext(),
                    invocationResult,
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );
            return questionContent;
        } catch (Exception exception) {
            log.warn("Follow-up generation failed: sessionId={}, error={}",
                    session.getId(), exception.getMessage());
            if (!properties.isFallbackEnabled()) {
                if (invocationResult != null) {
                    aiOperationGateway.submitInvocationOutcome(
                            invocationEnvelope.operationContext(),
                            invocationEnvelope.invocationContext(),
                            invocationResult,
                            ExecutionDisposition.EXECUTED,
                            InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                            exception.getMessage()
                    );
                }
                throw new BusinessException("INTERVIEW_011",
                        "Follow-up question generation failed. LLM service unavailable and fallback is disabled.");
            }
            log.info("Using deterministic fallback for follow-up: sessionId={}", session.getId());
            if (invocationResult != null) {
                aiOperationGateway.submitInvocationOutcome(
                        invocationEnvelope.operationContext(),
                        invocationEnvelope.invocationContext(),
                        invocationResult,
                        ExecutionDisposition.EXECUTED,
                        InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE,
                        exception.getMessage()
                );
            }
            return deterministicBuilder.buildFollowUpQuestion(parentQuestion, followUpIntent, branchDepth, locale.getTag());
        }
    }

    private void submitOutcome(
            LlmQuestionGenerationResult result,
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

    private InvocationEnvelope createInvocationEnvelope(Long userId, String resourceExternalId, String purpose) {
        String businessOperationId = purpose + "-" + UUID.randomUUID();
        return new InvocationEnvelope(
                aiOperationGateway.prepareOperation(new BusinessOperationContext(
                        businessOperationId,
                        userId,
                        RESOURCE_TYPE_INTERVIEW_SESSION,
                        resourceExternalId,
                        purpose,
                        List.of("INTERVIEW_CREDITS"),
                        Map.of()
                )),
                new AiInvocationContext(
                        businessOperationId + ":chat",
                        purpose,
                        UsageFamily.CHAT,
                        "INTERVIEW_CREDITS",
                        true,
                        Map.of()
                )
        );
    }

    private record LlmQuestionGenerationResult(
            List<InterviewQuestion> questions,
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

    private static class QuestionGenerationAttemptException extends RuntimeException {

        private final LlmQuestionGenerationResult result;
        private final Exception originalException;

        private QuestionGenerationAttemptException(Exception originalException, LlmQuestionGenerationResult result) {
            super(originalException.getMessage(), originalException);
            this.result = result;
            this.originalException = originalException;
        }

        private LlmQuestionGenerationResult result() {
            return result;
        }

        private Exception originalException() {
            return originalException;
        }
    }

    private Map<String, Object> buildMainQuestionTemplateVariables(
            InterviewSession session,
            Resume resume,
            ContentLocale locale,
            List<QuestionBlueprint> blueprints
    ) {
        return Map.of(
                "promptLanguage", locale.getPromptLanguage(),
                "jobTitle", session.getJobTitle(),
                "jobDescriptionSection", buildMultilineSection("Job Description", session.getJobDescription(), 4000),
                "resumeContentSection", buildMultilineSection(
                        "Candidate's Resume Summary",
                        resume == null ? null : resume.getParsedContent(),
                        4000
                ),
                "blueprintsSection", buildBlueprintsSection(blueprints)
        );
    }

    private Map<String, Object> buildFollowUpTemplateVariables(
            InterviewSession session,
            InterviewQuestion parentQuestion,
            String answerContent,
            InterviewFollowUpIntent followUpIntent,
            int branchDepth,
            String resumeContent,
            ContentLocale locale
    ) {
        return Map.of(
                "promptLanguage", locale.getPromptLanguage(),
                "mainQuestion", parentQuestion.getQuestionContent(),
                "answer", truncate(answerContent, 2000),
                "followUpIntent", followUpIntent.name(),
                "branchDepth", String.valueOf(branchDepth),
                "jobTitleSection", buildInlineSection("Job Title", session.getJobTitle()),
                "jobDescriptionSection", buildMultilineSection("Job Description", session.getJobDescription(), 1200),
                "resumeContentSection", buildMultilineSection("Resume Summary", resumeContent, 1500)
        );
    }

    private String buildBlueprintsSection(List<QuestionBlueprint> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Question Blueprints:\n");
        for (QuestionBlueprint blueprint : blueprints) {
            builder.append("- sequenceNumber=")
                    .append(blueprint.sequenceNumber())
                    .append(", questionType=")
                    .append(blueprint.questionType())
                    .append(", difficulty=")
                    .append(blueprint.difficulty())
                    .append(", estimatedMinutes=")
                    .append(blueprint.estimatedMinutes())
                    .append(", questionGoal=")
                    .append(blueprint.questionGoal())
                    .append(", focusHint=")
                    .append(blueprint.focusHint())
                    .append("\n");
        }
        return builder.toString();
    }

    private String buildMultilineSection(String title, String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return title + ":\n" + truncate(content, maxLength) + "\n";
    }

    private String buildInlineSection(String title, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return title + ": " + content + "\n";
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
