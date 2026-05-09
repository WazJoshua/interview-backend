package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.request.CreateInterviewRequest;
import com.josh.interviewj.interview.dto.response.CreateInterviewResponse;
import com.josh.interviewj.interview.dto.response.InterviewDetailResponse;
import com.josh.interviewj.interview.dto.response.InterviewListItemResponse;
import com.josh.interviewj.interview.dto.response.InterviewQuestionItemResponse;
import com.josh.interviewj.interview.dto.response.InterviewStartResponse;
import com.josh.interviewj.interview.model.InterviewMode;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewQuestionKind;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewProgressSnapshot;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private static final Set<String> ALLOWED_DIFFICULTIES = Set.of("JUNIOR", "MID", "SENIOR");

    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final InterviewQuestionGenerationService interviewQuestionGenerationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    private ChatEventRecorder chatEventRecorder;

    @Autowired(required = false)
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Autowired(required = false)
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    @Transactional
    public CreateInterviewResponse createInterview(String username, CreateInterviewRequest request) {
        User user = requireUser(username);
        Resume resume = resolveResume(request.getResumeId(), user.getId());
        InterviewMode interviewMode = request.getInterviewMode() == null ? InterviewMode.TEXT : request.getInterviewMode();
        if (interviewMode != InterviewMode.TEXT) {
            throw new BusinessException(ErrorCode.INTERVIEW_001, "Only TEXT interview mode is supported in phase 1");
        }

        String difficultyLevel = normalizeDifficulty(request.getDifficultyLevel());
        String contentLocale = ContentLocale.normalizeOrDefault(user.getLocale());
        UUID interviewId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();

        ChatSession chatSession = chatSessionRepository.save(ChatSession.builder()
                .externalId(chatSessionId)
                .userId(user.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(interviewId)
                .status(ChatSessionStatus.CREATED)
                .title(request.getJobTitle() == null ? "" : request.getJobTitle())
                .build());

        InterviewSession session = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(interviewId)
                .userId(user.getId())
                .resumeId(resume == null ? null : resume.getId())
                .chatSessionId(chatSession.getExternalId())
                .jobTitle(request.getJobTitle())
                .jobDescription(request.getJobDescription())
                .difficultyLevel(difficultyLevel)
                .durationMinutes(request.getDurationMinutes())
                .interviewMode(interviewMode)
                .contentLocale(contentLocale)
                .status(InterviewStatus.CREATED)
                .build());

        return new CreateInterviewResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                resume == null ? null : resume.getExternalId(),
                session.getJobTitle(),
                session.getDifficultyLevel(),
                session.getDurationMinutes(),
                session.getInterviewMode().name(),
                session.getStatus().name(),
                InterviewReportStatus.NOT_READY.name(),
                session.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public Page<InterviewListItemResponse> listInterviews(String username, int page, int size, InterviewStatus status) {
        User user = requireUser(username);
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<InterviewSession> sessions = status == null
                ? interviewSessionRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(user.getId(), pageRequest)
                : interviewSessionRepository.findByUserIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(user.getId(), status, pageRequest);
        return sessions.map(this::toListItem);
    }

    @Transactional(readOnly = true)
    public InterviewDetailResponse getInterviewDetail(String username, UUID interviewId) {
        User user = requireUser(username);
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        return toDetailResponse(session);
    }

    public InterviewStartResponse startInterview(String username, UUID interviewId) {
        User user = requireUser(username);
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        if (session.getStatus() != InterviewStatus.CREATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_006, "Interview can only be started from CREATED status");
        }

        String contentLocale = session.getContentLocale() == null
                ? ContentLocale.DEFAULT.getTag()
                : session.getContentLocale();
        ChatSession chatSession = chatSessionRepository.findByExternalId(session.getChatSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));

        Resume resume = null;
        if (session.getResumeId() != null) {
            resume = resumeRepository.findById(session.getResumeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Resume not found"));
            if (resume.getStatus() != ResumeStatus.PARSED) {
                throw new BusinessException(ErrorCode.INTERVIEW_008,
                        "Resume must be in PARSED status before starting interview");
            }
        }

        List<InterviewQuestion> questions = interviewQuestionGenerationService.generateMainQuestions(
                session,
                resume,
                contentLocale
        );

        return transactionTemplate.execute(status -> writeStartedInterview(user.getId(), interviewId, questions));
    }

    @Transactional
    protected InterviewStartResponse writeStartedInterview(Long userId, UUID interviewId, List<InterviewQuestion> generatedQuestions) {
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdForUpdate(interviewId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        if (session.getStatus() != InterviewStatus.CREATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_006, "Interview can only be started from CREATED status");
        }
        if (session.getContentLocale() == null) {
            session.setContentLocale(ContentLocale.DEFAULT.getTag());
        }

        ChatSession chatSession = chatSessionRepository.findByExternalId(session.getChatSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));

        List<InterviewQuestion> questions = generatedQuestions.stream()
                .map(question -> {
                    question.setSessionId(session.getId());
                    if (question.getExternalId() == null) {
                        question.setExternalId(UUID.randomUUID());
                    }
                    return question;
                })
                .toList();
        UUID promptMessageId = UUID.randomUUID();
        InterviewQuestion generatedCurrentQuestion = questions.isEmpty() ? null : questions.getFirst();
        if (generatedCurrentQuestion != null) {
            generatedCurrentQuestion.setPromptMessageId(promptMessageId);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage questionMessage = null;
        if (generatedCurrentQuestion != null) {
            questionMessage = chatMessageRepository.save(ChatMessage.builder()
                    .externalId(promptMessageId)
                    .chatSessionId(chatSession.getId())
                    .role(ChatRole.ASSISTANT)
                    .messageType(generatedCurrentQuestion.getQuestionKind() == InterviewQuestionKind.FOLLOW_UP
                            ? ChatMessageType.INTERVIEW_FOLLOW_UP
                            : ChatMessageType.INTERVIEW_QUESTION)
                    .content(generatedCurrentQuestion.getQuestionContent())
                    .metadata(serializeMetadata(Map.of(
                            "questionId", generatedCurrentQuestion.getExternalId(),
                            "sequenceNumber", generatedCurrentQuestion.getSequenceNumber(),
                            "questionKind", generatedCurrentQuestion.getQuestionKind().name()
                    )))
                    .sequenceNumber(chatSession.getNextMessageSequence())
                    .estimatedTokenCount(generatedCurrentQuestion.getQuestionContent().length())
                    .createdAt(now)
                    .build());
            chatSession.setNextMessageSequence(chatSession.getNextMessageSequence() + 1);
            chatSession.setMessageCount(chatSession.getMessageCount() + 1);
            chatSession.setLastMessagePreview(generatedCurrentQuestion.getQuestionContent());
            chatSession.setLastMessageAt(now);
        }
        List<InterviewQuestion> persistedQuestions = interviewQuestionRepository.saveAll(questions);
        InterviewQuestion currentQuestion = persistedQuestions.isEmpty() ? null : persistedQuestions.getFirst();

        chatSession.setStatus(ChatSessionStatus.ACTIVE);
        chatSessionRepository.save(chatSession);

        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setStartTime(now);
        session.setMainQuestionCount(persistedQuestions.size());
        session.setCurrentQuestionId(currentQuestion == null ? null : currentQuestion.getId());
        session.setCurrentBranchDepth(currentQuestion == null ? 0 : currentQuestion.getBranchDepth());
        session.setPendingFollowUpCount(0);
        session.setUsedFollowUpCount(0);
        session.setAnsweredMainQuestionCount(0);
        session.setIsCompletable(false);
        interviewSessionRepository.save(session);
        emitStartEvents(session, chatSession, currentQuestion, questionMessage);

        return new InterviewStartResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                session.getStatus().name(),
                reportStatus(session).name(),
                session.getStartTime(),
                session.getMainQuestionCount(),
                currentQuestion == null ? null : toQuestionItem(currentQuestion, null),
                progressSnapshot(session, currentQuestion)
        );
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));
    }

    private Resume resolveResume(UUID resumeExternalId, Long userId) {
        if (resumeExternalId == null) {
            return null;
        }
        return resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Resume not found"));
    }

    private String normalizeDifficulty(String difficultyLevel) {
        String normalized = difficultyLevel == null ? "MID" : difficultyLevel.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_DIFFICULTIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.INTERVIEW_001, "Unsupported difficulty level");
        }
        return normalized;
    }

    private InterviewListItemResponse toListItem(InterviewSession session) {
        return new InterviewListItemResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                session.getJobTitle(),
                session.getDifficultyLevel(),
                session.getInterviewMode().name(),
                session.getStatus().name(),
                reportStatus(session).name(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private InterviewDetailResponse toDetailResponse(InterviewSession session) {
        InterviewQuestion currentQuestion = resolveCurrentQuestion(session);
        Resume resume = session.getResumeId() == null
                ? null
                : resumeRepository.findById(session.getResumeId()).orElse(null);
        return new InterviewDetailResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                resume == null ? null : resume.getExternalId(),
                session.getJobTitle(),
                session.getJobDescription(),
                session.getDifficultyLevel(),
                session.getDurationMinutes(),
                session.getInterviewMode().name(),
                session.getStatus().name(),
                reportStatus(session).name(),
                session.getStartTime(),
                session.getEndTime(),
                progressSnapshot(session, currentQuestion),
                session.getRunningScore(),
                session.getCompletionReason() == null ? null : session.getCompletionReason().name(),
                session.getCreatedAt()
        );
    }

    private InterviewQuestion resolveCurrentQuestion(InterviewSession session) {
        if (session.getCurrentQuestionId() == null) {
            return null;
        }
        return interviewQuestionRepository.findById(session.getCurrentQuestionId()).orElse(null);
    }

    private InterviewProgressSnapshot progressSnapshot(InterviewSession session, InterviewQuestion currentQuestion) {
        return new InterviewProgressSnapshot(
                session.getMainQuestionCount(),
                session.getAnsweredMainQuestionCount(),
                currentQuestion == null ? null : currentQuestion.getExternalId(),
                currentQuestion == null ? null : currentQuestion.getQuestionKind().name(),
                session.getCurrentBranchDepth(),
                session.getUsedFollowUpCount(),
                session.getPendingFollowUpCount(),
                session.getIsCompletable()
        );
    }

    private InterviewQuestionItemResponse toQuestionItem(
            InterviewQuestion question,
            InterviewQuestionItemResponse.AnswerProjection answer
    ) {
        return new InterviewQuestionItemResponse(
                question.getExternalId(),
                question.getQuestionKind().name(),
                question.getFollowUpIntent() == null ? null : question.getFollowUpIntent().name(),
                null,
                question.getBranchDepth(),
                question.getSequenceNumber(),
                question.getQuestionType(),
                question.getQuestionContent(),
                question.getDifficulty(),
                question.getEstimatedMinutes(),
                answer
        );
    }

    private InterviewReportStatus reportStatus(InterviewSession session) {
        return interviewReportRepository.findBySessionId(session.getId())
                .map(report -> report.getStatus())
                .orElse(InterviewReportStatus.NOT_READY);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        Map<String, Object> ordered = new LinkedHashMap<>(metadata);
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void emitStartEvents(
            InterviewSession session,
            ChatSession chatSession,
            InterviewQuestion currentQuestion,
            ChatMessage questionMessage
    ) {
        if (currentQuestion == null || questionMessage == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "questionId", currentQuestion.getExternalId(),
                "questionKind", currentQuestion.getQuestionKind().name(),
                "sequenceNumber", currentQuestion.getSequenceNumber()
        );
        if (chatEventRecorder != null) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSession.getId(),
                    questionMessage.getId(),
                    ChatDomainType.INTERVIEW,
                    "INTERVIEW_STARTED",
                    "SUCCESS",
                    payload
            ));
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSession.getId(),
                    questionMessage.getId(),
                    ChatDomainType.INTERVIEW,
                    "QUESTION_PUSHED",
                    "SUCCESS",
                    payload
            ));
        }
        if (interviewWebSocketEventPublisher != null && interviewWebSocketPayloadFactory != null) {
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "INTERVIEW_STARTED",
                    questionMessage.getExternalId(),
                    payload
            ));
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    "QUESTION_PUSHED",
                    questionMessage.getExternalId(),
                    payload
            ));
        }
    }
}
