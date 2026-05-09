package com.josh.interviewj.interview.service;

import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.request.SubmitInterviewAnswerRequest;
import com.josh.interviewj.interview.dto.response.SubmitInterviewAnswerResponse;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewConcurrencyGuard;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling transactional write operations during interview answer submission.
 *
 * <p>This service is extracted from {@link InterviewAnswerCommandService} to ensure
 * that Spring AOP transaction proxy works correctly. The original implementation
 * suffered from self-invocation issue where {@code writeWithLock()} was called
 * from within the same class, bypassing the Spring transaction proxy.</p>
 *
 * <p>By extracting this to a separate bean, the transaction boundary is properly
 * enforced by Spring AOP when {@code InterviewAnswerCommandService} calls this service.</p>
 *
 * @see InterviewAnswerCommandService
 * @see <a href="docs/issues/2026-03-28-interview-answer-submit-no-active-transaction-after-llm-fallback.md">Issue documentation</a>
 */
@Service
@RequiredArgsConstructor
public class InterviewAnswerWriteService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final InterviewConcurrencyGuard interviewConcurrencyGuard;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ChatEventRecorder chatEventRecorder;

    @Autowired(required = false)
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Autowired(required = false)
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    /**
     * Execute the write phase of answer submission with pessimistic lock.
     *
     * <p>This method runs within a transaction to ensure atomicity of all write operations.
     * It re-reads the session with pessimistic lock to handle concurrent modifications.</p>
     *
     * @param snapshot the pre-computed snapshot from Phase 1
     * @param precomputed the pre-computed evaluation and transition result from Phase 2
     * @param request the original submission request
     * @return the submission response
     */
    @Transactional
    public SubmitInterviewAnswerResponse writeWithLock(
            AnswerSubmissionSnapshot snapshot,
            PrecomputedResult precomputed,
            SubmitInterviewAnswerRequest request
    ) {
        // Re-read session with lock
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdForUpdate(
                        snapshot.session().getExternalId(), snapshot.user().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));

        // Re-read question and check for existing answer
        InterviewQuestion question = interviewQuestionRepository.findBySessionIdAndExternalId(
                        session.getId(), snapshot.question().getExternalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Question not found"));

        Optional<InterviewAnswer> existingAnswer = interviewAnswerRepository.findBySessionIdAndQuestionId(
                session.getId(), question.getId());

        // Re-validate using the existing concurrency guard
        interviewConcurrencyGuard.assertCanSubmit(session, question, existingAnswer);

        // Validation passed - proceed with write
        return doWrite(session, question, snapshot, precomputed, request);
    }

    private SubmitInterviewAnswerResponse doWrite(
            InterviewSession session,
            InterviewQuestion question,
            AnswerSubmissionSnapshot snapshot,
            PrecomputedResult precomputed,
            SubmitInterviewAnswerRequest request
    ) {
        ChatSession chatSession = chatSessionRepository.findByIdForUpdate(snapshot.chatSession().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));
        Map<Long, InterviewQuestion> questionsById = snapshot.questionsById();
        List<InterviewAnswer> historicalAnswers = new ArrayList<>(snapshot.historicalAnswers());

        BranchTransition transition = precomputed.transition();

        UUID answerId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID evaluationMessageId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        InterviewAnswer answer = InterviewAnswer.builder()
                .externalId(answerId)
                .sessionId(session.getId())
                .questionId(question.getId())
                .answerContent(request.getAnswerContent())
                .durationSeconds(request.getDurationSeconds())
                .evaluationScore(precomputed.evaluationScore())
                .evaluationDetails(precomputed.evaluationDetailsJson())
                .referenceAnswer(null)
                .userMessageId(userMessageId)
                .evaluationMessageId(evaluationMessageId)
                .createdAt(now)
                .build();
        historicalAnswers.add(answer);

        // Update session state based on transition
        if (transition.followUpQuestion() != null && transition.followUpQuestion().getPromptMessageId() == null) {
            transition.followUpQuestion().setPromptMessageId(UUID.randomUUID());
        }
        if (transition.nextMainQuestion() != null && transition.nextMainQuestion().getPromptMessageId() == null) {
            transition.nextMainQuestion().setPromptMessageId(UUID.randomUUID());
        }

        // Apply transition to session
        applyTransitionToSession(session, transition);

        // Create chat messages
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(newChatMessage(
                userMessageId,
                chatSession.getId(),
                ChatRole.USER,
                ChatMessageType.INTERVIEW_ANSWER,
                request.getAnswerContent(),
                buildUserMetadata(question, answer),
                chatSession.getNextMessageSequence(),
                now
        ));
        messages.add(newChatMessage(
                evaluationMessageId,
                chatSession.getId(),
                ChatRole.ASSISTANT,
                ChatMessageType.INTERVIEW_EVALUATION,
                precomputed.envelope().overallComment(),
                buildEvaluationMetadata(question, answer, precomputed.evaluationScore(), transition.branchScore(), transition.runningScore()),
                chatSession.getNextMessageSequence() + 1,
                now
        ));
        if (transition.followUpQuestion() != null) {
            messages.add(newChatMessage(
                    transition.followUpQuestion().getPromptMessageId(),
                    chatSession.getId(),
                    ChatRole.ASSISTANT,
                    transition.followUpQuestion().getQuestionKind() == InterviewQuestionKind.FOLLOW_UP
                            ? ChatMessageType.INTERVIEW_FOLLOW_UP
                            : ChatMessageType.INTERVIEW_QUESTION,
                    transition.followUpQuestion().getQuestionContent(),
                    buildQuestionMetadata(transition.followUpQuestion()),
                    chatSession.getNextMessageSequence() + 2,
                    now
            ));
        } else if (transition.nextMainQuestion() != null) {
            messages.add(newChatMessage(
                    transition.nextMainQuestion().getPromptMessageId(),
                    chatSession.getId(),
                    ChatRole.ASSISTANT,
                    ChatMessageType.INTERVIEW_QUESTION,
                    transition.nextMainQuestion().getQuestionContent(),
                    buildQuestionMetadata(transition.nextMainQuestion()),
                    chatSession.getNextMessageSequence() + 2,
                    now
            ));
        }

        List<ChatMessage> savedMessages = chatMessageRepository.saveAll(messages);
        InterviewAnswer persistedAnswer = interviewAnswerRepository.save(answer);
        historicalAnswers.set(historicalAnswers.size() - 1, persistedAnswer);

        if (transition.followUpQuestion() != null) {
            shiftSubsequentQuestionsForFollowUp(session.getId(), transition.followUpQuestion().getSequenceNumber());
            InterviewQuestion persistedFollowUp = interviewQuestionRepository.save(transition.followUpQuestion());
            session.setCurrentQuestionId(persistedFollowUp.getId());
            transition = transition.withFollowUpQuestion(persistedFollowUp);
        }
        if (transition.nextMainQuestion() != null) {
            interviewQuestionRepository.save(transition.nextMainQuestion());
        }

        chatSession.setNextMessageSequence(chatSession.getNextMessageSequence() + savedMessages.size());
        chatSession.setMessageCount(chatSession.getMessageCount() + savedMessages.size());
        chatSession.setLastMessagePreview(savedMessages.getLast().getContent());
        chatSession.setLastMessageAt(now);
        chatSessionRepository.save(chatSession);

        interviewSessionRepository.save(session);
        emitAnswerEvents(session, chatSession, question, persistedAnswer, savedMessages, transition);

        return new SubmitInterviewAnswerResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                question.getExternalId(),
                persistedAnswer.getExternalId(),
                userMessageId,
                evaluationMessageId,
                precomputed.evaluationScore(),
                transition.branchScore(),
                transition.runningScore(),
                session.getAnsweredMainQuestionCount(),
                remainingMainQuestions(session),
                transition.nextAction().name(),
                toFollowUpResponse(transition.followUpQuestion(), questionsById),
                reportStatus(session).name()
        );
    }

    private void applyTransitionToSession(InterviewSession session, BranchTransition transition) {
        if (transition.followUpQuestion() != null) {
            session.setCurrentQuestionId(null); // Will be set after persist
            session.setCurrentBranchDepth(transition.followUpQuestion().getBranchDepth());
            session.setPendingFollowUpCount(1);
            session.setUsedFollowUpCount(session.getUsedFollowUpCount() + 1);
            session.setIsCompletable(false);
        } else {
            session.setAnsweredMainQuestionCount(session.getAnsweredMainQuestionCount() + 1);
            session.setCurrentQuestionId(transition.nextMainQuestion() == null ? null : transition.nextMainQuestion().getId());
            session.setCurrentBranchDepth(0);
            session.setPendingFollowUpCount(0);
            session.setIsCompletable(transition.nextMainQuestion() == null);
            session.setRunningScore(transition.runningScore());
        }
    }

    private void shiftSubsequentQuestionsForFollowUp(Long sessionId, Integer insertionSequenceNumber) {
        if (sessionId == null || insertionSequenceNumber == null) {
            return;
        }
        interviewQuestionRepository.incrementSequenceNumbersFrom(sessionId, insertionSequenceNumber);
    }

    private ChatMessage newChatMessage(
            UUID externalId,
            Long chatSessionId,
            ChatRole role,
            ChatMessageType messageType,
            String content,
            String metadata,
            int sequenceNumber,
            LocalDateTime createdAt
    ) {
        return ChatMessage.builder()
                .externalId(externalId)
                .chatSessionId(chatSessionId)
                .role(role)
                .messageType(messageType)
                .content(content)
                .metadata(metadata)
                .sequenceNumber(sequenceNumber)
                .estimatedTokenCount(content == null ? 0 : content.length())
                .createdAt(createdAt)
                .build();
    }

    private String buildUserMetadata(InterviewQuestion question, InterviewAnswer answer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("questionId", question.getExternalId());
        metadata.put("answerId", answer.getExternalId());
        metadata.put("questionKind", question.getQuestionKind().name());
        if (question.getFollowUpIntent() != null) {
            metadata.put("followUpIntent", question.getFollowUpIntent().name());
            metadata.put("followUpDepth", question.getBranchDepth());
        }
        return serialize(metadata);
    }

    private String buildEvaluationMetadata(
            InterviewQuestion question,
            InterviewAnswer answer,
            BigDecimal evaluationScore,
            BigDecimal branchScore,
            BigDecimal runningScore
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("questionId", question.getExternalId());
        metadata.put("answerId", answer.getExternalId());
        metadata.put("questionKind", question.getQuestionKind().name());
        metadata.put("evaluationScore", evaluationScore);
        if (question.getFollowUpIntent() != null) {
            metadata.put("followUpIntent", question.getFollowUpIntent().name());
            metadata.put("followUpDepth", question.getBranchDepth());
        }
        if (branchScore != null) {
            metadata.put("branchScore", branchScore);
        }
        if (runningScore != null) {
            metadata.put("runningScore", runningScore);
        }
        metadata.put("reportStatus", reportStatusForCurrentSession(question.getSessionId()));
        return serialize(metadata);
    }

    private String buildQuestionMetadata(InterviewQuestion question) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("questionId", question.getExternalId());
        metadata.put("sequenceNumber", question.getSequenceNumber());
        metadata.put("questionKind", question.getQuestionKind().name());
        if (question.getFollowUpIntent() != null) {
            metadata.put("followUpIntent", question.getFollowUpIntent().name());
            metadata.put("followUpDepth", question.getBranchDepth());
        }
        return serialize(metadata);
    }

    private InterviewReportStatus reportStatus(InterviewSession session) {
        return interviewReportRepository.findBySessionId(session.getId())
                .map(InterviewReport::getStatus)
                .orElse(InterviewReportStatus.NOT_READY);
    }

    private String reportStatusForCurrentSession(Long sessionId) {
        return interviewReportRepository.findBySessionId(sessionId)
                .map(report -> report.getStatus().name())
                .orElse(InterviewReportStatus.NOT_READY.name());
    }

    private int remainingMainQuestions(InterviewSession session) {
        int total = session.getMainQuestionCount() == null ? 0 : session.getMainQuestionCount();
        return Math.max(0, total - session.getAnsweredMainQuestionCount());
    }

    private SubmitInterviewAnswerResponse.FollowUpQuestionResponse toFollowUpResponse(
            InterviewQuestion question,
            Map<Long, InterviewQuestion> questionsById
    ) {
        if (question == null) {
            return null;
        }
        InterviewQuestion parent = question.getParentQuestionId() == null ? null : questionsById.get(question.getParentQuestionId());
        return new SubmitInterviewAnswerResponse.FollowUpQuestionResponse(
                question.getExternalId(),
                question.getQuestionKind().name(),
                question.getFollowUpIntent() == null ? null : question.getFollowUpIntent().name(),
                parent == null ? null : parent.getExternalId(),
                question.getBranchDepth(),
                question.getQuestionContent()
        );
    }

    private String serialize(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void emitAnswerEvents(
            InterviewSession session,
            ChatSession chatSession,
            InterviewQuestion question,
            InterviewAnswer answer,
            List<ChatMessage> savedMessages,
            BranchTransition transition
    ) {
        ChatMessage userMessage = savedMessages.getFirst();
        ChatMessage evaluationMessage = savedMessages.size() > 1 ? savedMessages.get(1) : null;
        ChatMessage followUpMessage = savedMessages.size() > 2 ? savedMessages.get(2) : null;

        if (chatEventRecorder != null) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSession.getId(),
                    userMessage.getId(),
                    ChatDomainType.INTERVIEW,
                    "ANSWER_ACCEPTED",
                    "SUCCESS",
                    Map.of("questionId", question.getExternalId(), "answerId", answer.getExternalId())
            ));
            if (evaluationMessage != null) {
                chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                        chatSession.getId(),
                        evaluationMessage.getId(),
                        ChatDomainType.INTERVIEW,
                        "EVALUATION_READY",
                        "SUCCESS",
                        Map.of(
                                "questionId", question.getExternalId(),
                                "answerId", answer.getExternalId(),
                                "evaluationScore", answer.getEvaluationScore()
                        )
                ));
            }
            if (transition.followUpQuestion() != null && followUpMessage != null) {
                chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                        chatSession.getId(),
                        followUpMessage.getId(),
                        ChatDomainType.INTERVIEW,
                        "FOLLOW_UP_CREATED",
                        "SUCCESS",
                        Map.of(
                                "questionId", transition.followUpQuestion().getExternalId(),
                                "parentQuestionId", question.getExternalId(),
                                "followUpIntent", transition.followUpQuestion().getFollowUpIntent().name(),
                                "followUpDepth", transition.followUpQuestion().getBranchDepth()
                        )
                ));
            } else if (transition.nextMainQuestion() != null && followUpMessage != null) {
                chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                        chatSession.getId(),
                        followUpMessage.getId(),
                        ChatDomainType.INTERVIEW,
                        "QUESTION_PUSHED",
                        "SUCCESS",
                        Map.of(
                                "questionId", transition.nextMainQuestion().getExternalId(),
                                "questionKind", transition.nextMainQuestion().getQuestionKind().name(),
                                "sequenceNumber", transition.nextMainQuestion().getSequenceNumber()
                        )
                ));
            }
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSession.getId(),
                    followUpMessage == null ? null : followUpMessage.getId(),
                    ChatDomainType.INTERVIEW,
                    "INTERVIEW_PROGRESS_UPDATED",
                    "SUCCESS",
                    progressPayload(session, transition)
            ));
        }

        if (interviewWebSocketEventPublisher != null && interviewWebSocketPayloadFactory != null) {
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "ANSWER_ACCEPTED",
                    userMessage.getExternalId(),
                    Map.of("questionId", question.getExternalId(), "answerId", answer.getExternalId())
            ));
            if (evaluationMessage != null) {
                Map<String, Object> evalPayload = new LinkedHashMap<>();
                evalPayload.put("questionId", question.getExternalId());
                evalPayload.put("answerId", answer.getExternalId());
                evalPayload.put("evaluationScore", answer.getEvaluationScore());
                evalPayload.put("branchScore", transition.branchScore());
                evalPayload.put("runningScore", transition.runningScore());
                interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                        session.getExternalId(),
                        session.getChatSessionId(),
                        "EVALUATION_READY",
                        evaluationMessage.getExternalId(),
                        evalPayload
                ));
            }
            if (transition.followUpQuestion() != null && followUpMessage != null) {
                interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                        session.getExternalId(),
                        session.getChatSessionId(),
                        "FOLLOW_UP_CREATED",
                        followUpMessage.getExternalId(),
                        Map.of(
                                "questionId", transition.followUpQuestion().getExternalId(),
                                "parentQuestionId", question.getExternalId(),
                                "followUpIntent", transition.followUpQuestion().getFollowUpIntent().name(),
                                "followUpDepth", transition.followUpQuestion().getBranchDepth()
                        )
                ));
            } else if (transition.nextMainQuestion() != null && followUpMessage != null) {
                interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                        session.getExternalId(),
                        session.getChatSessionId(),
                        "QUESTION_PUSHED",
                        followUpMessage.getExternalId(),
                        Map.of(
                                "questionId", transition.nextMainQuestion().getExternalId(),
                                "questionKind", transition.nextMainQuestion().getQuestionKind().name(),
                                "sequenceNumber", transition.nextMainQuestion().getSequenceNumber()
                        )
                ));
            }
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "INTERVIEW_PROGRESS_UPDATED",
                    followUpMessage == null ? null : followUpMessage.getExternalId(),
                    progressPayload(session, transition)
            ));
        }
    }

    private Map<String, Object> progressPayload(InterviewSession session, BranchTransition transition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentQuestionId", currentQuestionExternalId(transition));
        payload.put("answeredMainQuestionCount", session.getAnsweredMainQuestionCount());
        payload.put("pendingFollowUpCount", session.getPendingFollowUpCount());
        payload.put("runningScore", transition.runningScore());
        return payload;
    }

    private UUID currentQuestionExternalId(BranchTransition transition) {
        if (transition.followUpQuestion() != null) {
            return transition.followUpQuestion().getExternalId();
        }
        if (transition.nextMainQuestion() != null) {
            return transition.nextMainQuestion().getExternalId();
        }
        return null;
    }

    // ========== Internal Records (shared with InterviewAnswerCommandService) ==========

    /**
     * Snapshot of answer submission context.
     * Shared between InterviewAnswerCommandService and this service.
     */
    public record AnswerSubmissionSnapshot(
            com.josh.interviewj.auth.model.User user,
            InterviewSession session,
            InterviewQuestion question,
            InterviewAnswer existingAnswer,
            ChatSession chatSession,
            List<InterviewQuestion> questions,
            Map<Long, InterviewQuestion> questionsById,
            List<InterviewAnswer> historicalAnswers,
            String resumeContent
    ) {}

    /**
     * Pre-computed result from Phase 2 (LLM evaluation).
     * Shared between InterviewAnswerCommandService and this service.
     */
    public record PrecomputedResult(
            com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope envelope,
            BigDecimal evaluationScore,
            String evaluationDetailsJson,
            BranchTransition transition
    ) {}

    /**
     * Transition decision for session state.
     * Shared between InterviewAnswerCommandService and this service.
     */
    public record BranchTransition(
            com.josh.interviewj.interview.support.InterviewFollowUpPolicy.NextAction nextAction,
            InterviewQuestion followUpQuestion,
            InterviewQuestion nextMainQuestion,
            BigDecimal branchScore,
            BigDecimal runningScore
    ) {
        private BranchTransition withFollowUpQuestion(InterviewQuestion question) {
            return new BranchTransition(nextAction, question, nextMainQuestion, branchScore, runningScore);
        }
    }
}