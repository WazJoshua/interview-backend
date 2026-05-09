package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatEventRecorder;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.request.CompleteInterviewRequest;
import com.josh.interviewj.interview.dto.response.DeleteInterviewResponse;
import com.josh.interviewj.interview.dto.response.InterviewLifecycleResponse;
import com.josh.interviewj.interview.model.InterviewCompletionReason;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.websocket.InterviewWebSocketEventPublisher;
import com.josh.interviewj.interview.websocket.InterviewWebSocketPayloadFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
public class InterviewLifecycleService {

    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final InterviewReportService interviewReportService;
    private final ExecutorService virtualThreadExecutor;

    @Autowired(required = false)
    private ChatEventRecorder chatEventRecorder;

    @Autowired(required = false)
    private InterviewWebSocketEventPublisher interviewWebSocketEventPublisher;

    @Autowired(required = false)
    private InterviewWebSocketPayloadFactory interviewWebSocketPayloadFactory;

    @Transactional
    public InterviewLifecycleResponse endInterview(
            String username,
            UUID interviewId,
            CompleteInterviewRequest request
    ) {
        User user = requireUser(username);
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdForUpdate(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERVIEW_007, "Interview is not in progress");
        }

        InterviewCompletionReason completionReason = resolveCompletionReason(session, request.getCompletionReason());
        ChatSession chatSession = requireChatSessionForUpdate(session.getChatSessionId());
        InterviewReport report = finalizeLockedSession(session, chatSession, completionReason, true, false);

        return new InterviewLifecycleResponse(
                session.getExternalId(),
                session.getChatSessionId(),
                session.getStatus().name(),
                session.getCompletionReason().name(),
                report.getStatus().name(),
                session.getEndTime()
        );
    }

    @Transactional
    public DeleteInterviewResponse deleteInterview(String username, UUID interviewId) {
        User user = requireUser(username);
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdForUpdate(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        if (session.getStatus() == InterviewStatus.IN_PROGRESS) {
            ChatSession chatSession = requireChatSessionForUpdate(session.getChatSessionId());
            finalizeLockedSession(session, chatSession, InterviewCompletionReason.ABORTED, false, true);
        } else {
            session.setDeletedAt(LocalDateTime.now());
            interviewSessionRepository.save(session);
        }
        return new DeleteInterviewResponse(session.getExternalId(), true);
    }

    @Transactional
    boolean abortInterview(Long sessionId, LocalDateTime timeoutCutoff, boolean softDeleteAfterAbort) {
        InterviewSession session = interviewSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        if (session.getDeletedAt() != null || session.getStatus() != InterviewStatus.IN_PROGRESS) {
            return false;
        }
        ChatSession chatSession = requireChatSessionForUpdate(session.getChatSessionId());
        if (timeoutCutoff != null && !isTimedOut(session, chatSession, timeoutCutoff)) {
            return false;
        }
        finalizeLockedSession(session, chatSession, InterviewCompletionReason.ABORTED, false, softDeleteAfterAbort);
        return true;
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));
    }

    private InterviewCompletionReason resolveCompletionReason(
            InterviewSession session,
            InterviewCompletionReason requestedReason
    ) {
        if (requestedReason == InterviewCompletionReason.ABORTED) {
            return InterviewCompletionReason.ABORTED;
        }
        boolean fullyCompleted = Boolean.TRUE.equals(session.getIsCompletable())
                && session.getCurrentQuestionId() == null
                && session.getMainQuestionCount() != null
                && session.getAnsweredMainQuestionCount() != null
                && session.getAnsweredMainQuestionCount() >= session.getMainQuestionCount()
                && (session.getPendingFollowUpCount() == null || session.getPendingFollowUpCount() == 0);
        if (requestedReason == InterviewCompletionReason.COMPLETED_ALL && !fullyCompleted) {
            throw new BusinessException(ErrorCode.INTERVIEW_001, "completionReason COMPLETED_ALL does not match interview progress");
        }
        if (fullyCompleted) {
            return InterviewCompletionReason.COMPLETED_ALL;
        }
        return InterviewCompletionReason.USER_EARLY_END;
    }

    private InterviewReport finalizeLockedSession(
            InterviewSession session,
            ChatSession chatSession,
            InterviewCompletionReason completionReason,
            boolean generateReport,
            boolean softDeleteAfterAbort
    ) {
        LocalDateTime now = LocalDateTime.now();
        session.setCompletionReason(completionReason);
        session.setEndTime(now);
        session.setStatus(resolveSessionStatus(completionReason));
        chatSession.setStatus(resolveChatSessionStatus(completionReason));
        if (softDeleteAfterAbort) {
            session.setDeletedAt(now);
        }

        InterviewReport report = generateReport ? interviewReportService.prepareReportGeneration(session) : null;
        interviewSessionRepository.save(session);
        chatSessionRepository.save(chatSession);
        emitLifecycleEvents(session, chatSession, report, generateReport);
        if (generateReport && report != null) {
            runAfterCommit(() -> virtualThreadExecutor.submit(() -> interviewReportService.generateReport(session.getId())));
        }
        return report;
    }

    private ChatSession requireChatSessionForUpdate(UUID chatSessionId) {
        return chatSessionRepository.findByExternalIdForUpdate(chatSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_005, "Chat session not found"));
    }

    private boolean isTimedOut(InterviewSession session, ChatSession chatSession, LocalDateTime cutoff) {
        LocalDateTime lastActivityAt = chatSession.getLastMessageAt();
        if (lastActivityAt == null) {
            lastActivityAt = session.getStartTime();
        }
        if (lastActivityAt == null) {
            lastActivityAt = session.getUpdatedAt();
        }
        return lastActivityAt != null && lastActivityAt.isBefore(cutoff);
    }

    private InterviewStatus resolveSessionStatus(InterviewCompletionReason completionReason) {
        return completionReason == InterviewCompletionReason.ABORTED
                ? InterviewStatus.ABORTED
                : InterviewStatus.COMPLETED;
    }

    private ChatSessionStatus resolveChatSessionStatus(InterviewCompletionReason completionReason) {
        return completionReason == InterviewCompletionReason.ABORTED
                ? ChatSessionStatus.ABORTED
                : ChatSessionStatus.COMPLETED;
    }

    private void runAfterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
            return;
        }
        runnable.run();
    }

    private void emitLifecycleEvents(
            InterviewSession session,
            ChatSession chatSession,
            InterviewReport report,
            boolean generateReport
    ) {
        String interviewEventType = generateReport ? "INTERVIEW_COMPLETED" : "INTERVIEW_ABORTED";
        if (chatEventRecorder != null) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    chatSession.getId(),
                    null,
                    ChatDomainType.INTERVIEW,
                    interviewEventType,
                    "SUCCESS",
                    Map.of("completionReason", session.getCompletionReason().name(), "status", session.getStatus().name())
            ));
            if (generateReport && report != null) {
                chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                        chatSession.getId(),
                        null,
                        ChatDomainType.INTERVIEW,
                        "REPORT_GENERATING",
                        "SUCCESS",
                        Map.of("reportId", report.getExternalId(), "reportStatus", report.getStatus().name())
                ));
            }
        }
        if (interviewWebSocketEventPublisher != null && interviewWebSocketPayloadFactory != null) {
            interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                    session.getExternalId(),
                    session.getChatSessionId(),
                    interviewEventType,
                    null,
                    Map.of("completionReason", session.getCompletionReason().name(), "status", session.getStatus().name())
            ));
            if (generateReport && report != null) {
                interviewWebSocketEventPublisher.publishAfterCommit(interviewWebSocketPayloadFactory.create(
                        session.getExternalId(),
                        session.getChatSessionId(),
                        "REPORT_GENERATING",
                        null,
                        Map.of("reportId", report.getExternalId(), "reportStatus", report.getStatus().name())
                ));
            }
        }
    }
}
