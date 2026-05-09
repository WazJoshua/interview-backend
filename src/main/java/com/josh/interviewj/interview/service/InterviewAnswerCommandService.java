package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.request.SubmitInterviewAnswerRequest;
import com.josh.interviewj.interview.dto.response.SubmitInterviewAnswerResponse;
import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.interview.support.InterviewFollowUpPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for handling interview answer submissions.
 *
 * <p>Implements a two-phase transaction pattern:
 * <ol>
 *   <li>Phase 1: Read snapshot (non-transactional) - fetch all necessary data</li>
 *   <li>Phase 2: LLM evaluation (non-transactional) - evaluate answer and generate follow-up</li>
 *   <li>Phase 3: Write transaction (short transaction) - persist all changes with lock</li>
 * </ol>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class InterviewAnswerCommandService {

    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ResumeRepository resumeRepository;
    private final InterviewEvaluationService interviewEvaluationService;
    private final InterviewQuestionGenerationService interviewQuestionGenerationService;
    private final InterviewScoringService interviewScoringService;
    private final InterviewFollowUpPolicy interviewFollowUpPolicy;
    private final InterviewAnswerWriteService interviewAnswerWriteService;
    private final ObjectMapper objectMapper;

    

    /**
     * Submit an answer to an interview question.
     *
     * <p>Uses two-phase transaction pattern to avoid holding locks during LLM calls.</p>
     *
     * @param username the username
     * @param interviewId the interview session ID
     * @param questionId the question ID
     * @param request the answer request
     * @return the submission response
     */
    public SubmitInterviewAnswerResponse submitAnswer(
            String username,
            UUID interviewId,
            UUID questionId,
            SubmitInterviewAnswerRequest request
    ) {
        // Phase 1: Read snapshot (non-transactional)
        AnswerSubmissionSnapshot snapshot = readSnapshot(username, interviewId, questionId);

        // Phase 2: Evaluate answer and compute follow-up (non-transactional)
        PrecomputedResult precomputed = computeEvaluationAndTransition(
                snapshot,
                request.getAnswerContent(),
                request.getDurationSeconds()
        );

        // Phase 3: Write with lock (transactional) - delegate to separate service
        // to ensure Spring AOP transaction proxy works correctly
        return interviewAnswerWriteService.writeWithLock(
                toWriteServiceSnapshot(snapshot),
                toWriteServicePrecomputed(precomputed),
                request
        );
    }

    // ========== Phase 1: Read Snapshot ==========

    private AnswerSubmissionSnapshot readSnapshot(String username, UUID interviewId, UUID questionId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));

        // Read session WITHOUT lock for snapshot
        InterviewSession sessionSnapshot = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));

        InterviewQuestion question = interviewQuestionRepository.findBySessionIdAndExternalId(
                        sessionSnapshot.getId(), questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Question not found"));

        Optional<InterviewAnswer> existingAnswer = interviewAnswerRepository.findBySessionIdAndQuestionId(
                sessionSnapshot.getId(), question.getId());

        ChatSession chatSession = chatSessionRepository.findByExternalId(sessionSnapshot.getChatSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));

        List<InterviewQuestion> questions = interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(
                sessionSnapshot.getId());
        Map<Long, InterviewQuestion> questionsById = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()));

        List<InterviewAnswer> historicalAnswers = new ArrayList<>(
                interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(sessionSnapshot.getId()));

        return new AnswerSubmissionSnapshot(
                user,
                sessionSnapshot,
                question,
                existingAnswer.orElse(null),
                chatSession,
                questions,
                questionsById,
                historicalAnswers,
                resolveResumeContent(sessionSnapshot)
        );
    }

    private String resolveResumeContent(InterviewSession session) {
        if (session.getResumeId() == null) {
            return null;
        }
        return resumeRepository.findById(session.getResumeId())
                .map(resume -> resume.getParsedContent())
                .orElse(null);
    }

    // ========== Phase 2: Compute Evaluation and Transition ==========

    private PrecomputedResult computeEvaluationAndTransition(
            AnswerSubmissionSnapshot snapshot,
            String answerContent,
            Integer durationSeconds
    ) {
        // Evaluate answer using LLM with fallback
        InterviewEvaluationEnvelope envelope = interviewEvaluationService.evaluateAnswer(
                snapshot.question(),
                answerContent,
                durationSeconds,
                snapshot.session().getJobTitle(),
                snapshot.session().getContentLocale()
        );

        BigDecimal evaluationScore = calculateScoreFromEnvelope(envelope, answerContent, durationSeconds);

        // Compute branch transition
        BranchTransition transition = computeTransition(
                snapshot.session(),
                snapshot.question(),
                snapshot.questions(),
                snapshot.questionsById(),
                snapshot.historicalAnswers(),
                evaluationScore,
                answerContent,
                snapshot.resumeContent()
        );

        // Build evaluation details JSON
        String evaluationDetailsJson = serializeEnvelope(envelope);

        return new PrecomputedResult(
                envelope,
                evaluationScore,
                evaluationDetailsJson,
                transition
        );
    }

    private BigDecimal calculateScoreFromEnvelope(
            InterviewEvaluationEnvelope envelope,
            String answerContent,
            Integer durationSeconds
    ) {
        BigDecimal weightedScore = envelope.calculateWeightedScore();
        if (weightedScore != null) {
            return weightedScore;
        }
        // Fallback to heuristic score
        return heuristicScore(answerContent, durationSeconds);
    }

    private BigDecimal heuristicScore(String answerContent, Integer durationSeconds) {
        String content = answerContent == null ? "" : answerContent.trim();
        int lengthScore = Math.min(45, content.length() / 8);
        int structureBonus = hasStructure(content) ? 10 : 0;
        int detailBonus = hasDetail(content) ? 8 : 0;
        int durationBonus = durationSeconds == null ? 0 : Math.min(7, durationSeconds / 30);
        return BigDecimal.valueOf(Math.min(100, 35 + lengthScore + structureBonus + detailBonus + durationBonus));
    }

    private boolean hasStructure(String content) {
        String lower = content.toLowerCase();
        return lower.contains("first") || lower.contains("首先")
                || lower.contains("second") || lower.contains("其次")
                || lower.contains("finally") || lower.contains("最后")
                || lower.contains("1.") || lower.contains("2.");
    }

    private boolean hasDetail(String content) {
        String lower = content.toLowerCase();
        return lower.contains("because") || lower.contains("因为")
                || lower.contains("therefore") || lower.contains("所以")
                || lower.contains("for example") || lower.contains("例如")
                || lower.contains("specifically") || lower.contains("具体");
    }

    private String serializeEnvelope(InterviewEvaluationEnvelope envelope) {
        try {
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
            payload.put("evidence", envelope.evidence());
            payload.put("risks", envelope.risks());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize evaluation envelope", exception);
        }
    }

    private BranchTransition computeTransition(
            InterviewSession session,
            InterviewQuestion question,
            List<InterviewQuestion> questions,
            Map<Long, InterviewQuestion> questionsById,
            List<InterviewAnswer> answers,
            BigDecimal evaluationScore,
            String answerContent,
            String resumeContent
    ) {
        Map<Long, InterviewAnswer> answersByQuestionId = answers.stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, Function.identity(), (left, right) -> right));
        InterviewQuestion rootMainQuestion = resolveRootMainQuestion(question, questionsById);

        if (question.getQuestionKind() == InterviewQuestionKind.MAIN) {
            InterviewFollowUpPolicy.Decision decision = interviewFollowUpPolicy.decideForMainQuestion(
                    evaluationScore,
                    session.getUsedFollowUpCount()
            );
            if (decision.nextAction() == InterviewFollowUpPolicy.NextAction.FOLLOW_UP) {
                InterviewQuestion followUp = createFollowUpQuestion(
                        session,
                        question,
                        decision.followUpIntent(),
                        decision.nextDepth(),
                        questions,
                        answerContent,
                        resumeContent
                );
                return new BranchTransition(decision.nextAction(), followUp, null, null, session.getRunningScore());
            }
            BigDecimal branchScore = interviewScoringService.finalizeMainOnly(evaluationScore);
            return finalizeBranch(session, rootMainQuestion, questions, answersByQuestionId, branchScore);
        }

        if (question.getFollowUpIntent() == InterviewFollowUpIntent.CLARIFY) {
            BigDecimal mainScore = answersByQuestionId.get(rootMainQuestion.getId()).getEvaluationScore();
            BigDecimal branchScore = interviewScoringService.finalizeClarifyBranch(mainScore, evaluationScore);
            return finalizeBranch(session, rootMainQuestion, questions, answersByQuestionId, branchScore);
        }

        InterviewFollowUpPolicy.Decision decision = interviewFollowUpPolicy.decideForFollowUpQuestion(
                question.getFollowUpIntent(),
                evaluationScore,
                question.getBranchDepth(),
                session.getUsedFollowUpCount()
        );
        if (decision.nextAction() == InterviewFollowUpPolicy.NextAction.FOLLOW_UP) {
            InterviewQuestion followUp = createFollowUpQuestion(
                    session,
                    question,
                    decision.followUpIntent(),
                    decision.nextDepth(),
                    questions,
                    answerContent,
                    resumeContent
            );
            return new BranchTransition(decision.nextAction(), followUp, null, null, session.getRunningScore());
        }

        // Include current answer's score in deep-dive scores
        // (current answer is not yet in answersByQuestionId as it hasn't been persisted)
        List<BigDecimal> deepDiveScores = new ArrayList<>(collectDeepDiveScores(rootMainQuestion, questions, answersByQuestionId));
        deepDiveScores.add(evaluationScore);
        BigDecimal mainScore = answersByQuestionId.get(rootMainQuestion.getId()).getEvaluationScore();
        BigDecimal branchScore = interviewScoringService.finalizeDeepDiveBranch(mainScore, deepDiveScores);
        return finalizeBranch(session, rootMainQuestion, questions, answersByQuestionId, branchScore);
    }

    private BranchTransition finalizeBranch(
            InterviewSession session,
            InterviewQuestion rootMainQuestion,
            List<InterviewQuestion> questions,
            Map<Long, InterviewAnswer> answersByQuestionId,
            BigDecimal currentBranchScore
    ) {
        List<BigDecimal> historicalFinalizedScores = finalizedBranchScores(questions, answersByQuestionId, rootMainQuestion.getId());
        historicalFinalizedScores.add(currentBranchScore);
        BigDecimal runningScore = interviewScoringService.calculateRunningScore(historicalFinalizedScores);

        InterviewQuestion nextMainQuestion = findNextUnansweredMainQuestion(questions, answersByQuestionId, rootMainQuestion.getSequenceNumber());

        InterviewFollowUpPolicy.NextAction nextAction = nextMainQuestion == null
                ? InterviewFollowUpPolicy.NextAction.INTERVIEW_COMPLETABLE
                : InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION;
        return new BranchTransition(nextAction, null, nextMainQuestion, currentBranchScore, runningScore);
    }

    // ========== Helper Methods ==========

    private InterviewQuestion createFollowUpQuestion(
            InterviewSession session,
            InterviewQuestion parentQuestion,
            InterviewFollowUpIntent followUpIntent,
            int branchDepth,
            List<InterviewQuestion> questions,
            String answerContent,
            String resumeContent
    ) {
        int nextSequence = parentQuestion.getSequenceNumber() + 1;
        UUID promptMessageId = UUID.randomUUID();
        InterviewQuestion followUp = InterviewQuestion.builder()
                .sessionId(session.getId())
                .externalId(UUID.randomUUID())
                .questionKind(InterviewQuestionKind.FOLLOW_UP)
                .followUpIntent(followUpIntent)
                .parentQuestionId(parentQuestion.getId())
                .branchDepth(branchDepth)
                .sequenceNumber(nextSequence)
                .questionType(parentQuestion.getQuestionType())
                .questionContent(interviewQuestionGenerationService.generateFollowUpQuestionContent(
                        session,
                        parentQuestion,
                        answerContent,
                        followUpIntent,
                        branchDepth,
                        resumeContent
                ))
                .difficulty(Math.min(5, parentQuestion.getDifficulty() + 1))
                .estimatedMinutes(2)
                .promptMessageId(promptMessageId)
                .build();
        return followUp;
    }

    private InterviewQuestion resolveRootMainQuestion(
            InterviewQuestion question,
            Map<Long, InterviewQuestion> questionsById
    ) {
        InterviewQuestion current = question;
        while (current.getParentQuestionId() != null) {
            current = questionsById.get(current.getParentQuestionId());
        }
        return current;
    }

    private List<BigDecimal> collectDeepDiveScores(
            InterviewQuestion rootMainQuestion,
            List<InterviewQuestion> questions,
            Map<Long, InterviewAnswer> answersByQuestionId
    ) {
        return questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.FOLLOW_UP)
                .filter(q -> q.getFollowUpIntent() == InterviewFollowUpIntent.DEEP_DIVE)
                .filter(q -> belongsToRoot(q, rootMainQuestion.getId(), questions.stream()
                        .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()))))
                .sorted(Comparator.comparingInt(InterviewQuestion::getBranchDepth))
                .map(q -> answersByQuestionId.get(q.getId()))
                .filter(java.util.Objects::nonNull)
                .map(InterviewAnswer::getEvaluationScore)
                .toList();
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

    private List<BigDecimal> finalizedBranchScores(
            List<InterviewQuestion> questions,
            Map<Long, InterviewAnswer> answersByQuestionId,
            Long excludeRootMainQuestionId
    ) {
        Map<Long, InterviewQuestion> questionsById = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, Function.identity()));
        List<InterviewQuestion> mainQuestions = questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.MAIN)
                .sorted(Comparator.comparingInt(InterviewQuestion::getSequenceNumber))
                .toList();
        List<BigDecimal> finalized = new ArrayList<>();
        for (InterviewQuestion mainQuestion : mainQuestions) {
            if (mainQuestion.getId().equals(excludeRootMainQuestionId)) {
                continue;
            }
            InterviewAnswer mainAnswer = answersByQuestionId.get(mainQuestion.getId());
            if (mainAnswer == null) {
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
                    finalized.add(interviewScoringService.finalizeMainOnly(mainAnswer.getEvaluationScore()));
                }
                continue;
            }
            if (branchQuestions.isEmpty()) {
                finalized.add(interviewScoringService.finalizeMainOnly(mainAnswer.getEvaluationScore()));
                continue;
            }
            InterviewQuestion lastQuestion = branchQuestions.getLast();
            if (lastQuestion.getFollowUpIntent() == InterviewFollowUpIntent.CLARIFY) {
                finalized.add(interviewScoringService.finalizeClarifyBranch(
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
            finalized.add(interviewScoringService.finalizeDeepDiveBranch(mainAnswer.getEvaluationScore(), deepDiveScores));
        }
        return finalized;
    }

    private InterviewQuestion findNextUnansweredMainQuestion(
            List<InterviewQuestion> questions,
            Map<Long, InterviewAnswer> answersByQuestionId,
            int currentSequenceNumber
    ) {
        return questions.stream()
                .filter(q -> q.getQuestionKind() == InterviewQuestionKind.MAIN)
                .filter(q -> q.getSequenceNumber() > currentSequenceNumber)
                .filter(q -> !answersByQuestionId.containsKey(q.getId()))
                .min(Comparator.comparingInt(InterviewQuestion::getSequenceNumber))
                .orElse(null);
    }

    // ========== Conversion to WriteService Types ==========

    private InterviewAnswerWriteService.AnswerSubmissionSnapshot toWriteServiceSnapshot(AnswerSubmissionSnapshot snapshot) {
        return new InterviewAnswerWriteService.AnswerSubmissionSnapshot(
                snapshot.user(),
                snapshot.session(),
                snapshot.question(),
                snapshot.existingAnswer(),
                snapshot.chatSession(),
                snapshot.questions(),
                snapshot.questionsById(),
                snapshot.historicalAnswers(),
                snapshot.resumeContent()
        );
    }

    private InterviewAnswerWriteService.PrecomputedResult toWriteServicePrecomputed(PrecomputedResult precomputed) {
        return new InterviewAnswerWriteService.PrecomputedResult(
                precomputed.envelope(),
                precomputed.evaluationScore(),
                precomputed.evaluationDetailsJson(),
                toWriteServiceBranchTransition(precomputed.transition())
        );
    }

    private InterviewAnswerWriteService.BranchTransition toWriteServiceBranchTransition(BranchTransition transition) {
        return new InterviewAnswerWriteService.BranchTransition(
                transition.nextAction(),
                transition.followUpQuestion(),
                transition.nextMainQuestion(),
                transition.branchScore(),
                transition.runningScore()
        );
    }

    // ========== Internal Records ==========

    private record AnswerSubmissionSnapshot(
            User user,
            InterviewSession session,
            InterviewQuestion question,
            InterviewAnswer existingAnswer,
            ChatSession chatSession,
            List<InterviewQuestion> questions,
            Map<Long, InterviewQuestion> questionsById,
            List<InterviewAnswer> historicalAnswers,
            String resumeContent
    ) {}

    private record PrecomputedResult(
            InterviewEvaluationEnvelope envelope,
            BigDecimal evaluationScore,
            String evaluationDetailsJson,
            BranchTransition transition
    ) {}

    private record BranchTransition(
            InterviewFollowUpPolicy.NextAction nextAction,
            InterviewQuestion followUpQuestion,
            InterviewQuestion nextMainQuestion,
            BigDecimal branchScore,
            BigDecimal runningScore
    ) {}
}