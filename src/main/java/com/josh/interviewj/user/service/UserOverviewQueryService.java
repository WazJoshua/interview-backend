package com.josh.interviewj.user.service;

import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.user.dto.response.UserOverviewResponse;
import com.josh.interviewj.user.repository.UserInterviewOverviewReadRepository;
import com.josh.interviewj.user.repository.UserKnowledgeBaseOverviewReadRepository;
import com.josh.interviewj.user.repository.UserResumeOverviewReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserOverviewQueryService {

    private static final BigDecimal ZERO_SCORE = new BigDecimal("0.00");

    private final UserAccessService userAccessService;
    private final UserResumeOverviewReadRepository userResumeOverviewReadRepository;
    private final UserInterviewOverviewReadRepository userInterviewOverviewReadRepository;
    private final UserKnowledgeBaseOverviewReadRepository userKnowledgeBaseOverviewReadRepository;

    @Transactional(readOnly = true)
    public UserOverviewResponse getOverview(UUID targetUserId, String currentUsername) {
        Long internalUserId = userAccessService.requireVisibleUser(targetUserId, currentUsername).getId();

        return UserOverviewResponse.builder()
                .resumeAverageScore(scaleScore(userResumeOverviewReadRepository.findAverageScoreByUserId(internalUserId)))
                .interviewAverageScore(scaleScore(userInterviewOverviewReadRepository.findAverageScoreByUserId(internalUserId)))
                .mockInterviewCompletedCount(userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(internalUserId))
                .recentActivity(UserOverviewResponse.RecentActivity.builder()
                        .latestInterview(userInterviewOverviewReadRepository.findLatestInterviewActivity(internalUserId)
                                .map(this::toLatestInterview)
                                .orElse(null))
                        .latestResume(userResumeOverviewReadRepository.findLatestResumeActivity(internalUserId)
                                .map(this::toLatestResume)
                                .orElse(null))
                        .latestKnowledgeBaseQuestion(userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(internalUserId)
                                .map(this::toLatestKnowledgeBaseQuestion)
                                .orElse(null))
                        .build())
                .build();
    }

    private UserOverviewResponse.LatestInterview toLatestInterview(
            UserInterviewOverviewReadRepository.LatestInterviewProjection projection
    ) {
        String reportStatus = projection.getReportStatus() == null || projection.getReportStatus().isBlank()
                ? InterviewReportStatus.NOT_READY.name()
                : projection.getReportStatus();

        return UserOverviewResponse.LatestInterview.builder()
                .interviewId(projection.getInterviewId())
                .status(projection.getStatus())
                .reportStatus(reportStatus)
                .score(InterviewReportStatus.READY.name().equals(reportStatus) && projection.getScore() != null
                        ? scaleScore(projection.getScore())
                        : null)
                .occurredAt(projection.getOccurredAt())
                .build();
    }

    private UserOverviewResponse.LatestResume toLatestResume(UserResumeOverviewReadRepository.LatestResumeProjection projection) {
        String uploadStatus = projection.getUploadStatus();
        String analysisStatus = projection.getAnalysisStatus();

        return UserOverviewResponse.LatestResume.builder()
                .resumeId(projection.getResumeId())
                .fileName(projection.getFileName())
                .uploadStatus(uploadStatus)
                .uploadedAt(projection.getUploadedAt())
                .parsed(ResumeStatus.PARSED.name().equals(uploadStatus))
                .parsedAt(projection.getParsedAt())
                .analysisStatus(analysisStatus)
                .analyzed(AnalysisStatus.COMPLETED.name().equals(analysisStatus))
                .analysisAt(projection.getAnalysisAt())
                .build();
    }

    private UserOverviewResponse.LatestKnowledgeBaseQuestion toLatestKnowledgeBaseQuestion(
            UserKnowledgeBaseOverviewReadRepository.LatestKnowledgeBaseQuestionProjection projection
    ) {
        return UserOverviewResponse.LatestKnowledgeBaseQuestion.builder()
                .kbId(projection.getKbId())
                .kbName(projection.getKbName())
                .question(projection.getQuestion())
                .askedAt(projection.getAskedAt())
                .build();
    }

    private BigDecimal scaleScore(BigDecimal value) {
        if (value == null) {
            return ZERO_SCORE;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
