package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.user.dto.response.UserOverviewResponse;
import com.josh.interviewj.user.repository.UserInterviewOverviewReadRepository;
import com.josh.interviewj.user.repository.UserKnowledgeBaseOverviewReadRepository;
import com.josh.interviewj.user.repository.UserResumeOverviewReadRepository;
import com.josh.interviewj.user.service.UserAccessService;
import com.josh.interviewj.user.service.UserOverviewQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserOverviewQueryServiceTest {

    @Mock
    private UserAccessService userAccessService;

    @Mock
    private UserResumeOverviewReadRepository userResumeOverviewReadRepository;

    @Mock
    private UserInterviewOverviewReadRepository userInterviewOverviewReadRepository;

    @Mock
    private UserKnowledgeBaseOverviewReadRepository userKnowledgeBaseOverviewReadRepository;

    @InjectMocks
    private UserOverviewQueryService userOverviewQueryService;

    @Test
    void getOverview_NoData_ReturnsStableEmptyState() {
        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder()
                .id(101L)
                .externalId(targetUserId)
                .username("owner")
                .build();
        when(userAccessService.requireVisibleUser(targetUserId, "owner")).thenReturn(targetUser);
        when(userResumeOverviewReadRepository.findAverageScoreByUserId(101L)).thenReturn(null);
        when(userResumeOverviewReadRepository.findLatestResumeActivity(101L)).thenReturn(Optional.empty());
        when(userInterviewOverviewReadRepository.findAverageScoreByUserId(101L)).thenReturn(null);
        when(userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(101L)).thenReturn(0L);
        when(userInterviewOverviewReadRepository.findLatestInterviewActivity(101L)).thenReturn(Optional.empty());
        when(userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(101L)).thenReturn(Optional.empty());

        UserOverviewResponse response = userOverviewQueryService.getOverview(targetUserId, "owner");

        assertEquals(new BigDecimal("0.00"), response.getResumeAverageScore());
        assertEquals(new BigDecimal("0.00"), response.getInterviewAverageScore());
        assertEquals(0L, response.getMockInterviewCompletedCount());
        assertNotNull(response.getRecentActivity());
        assertNull(response.getRecentActivity().getLatestInterview());
        assertNull(response.getRecentActivity().getLatestResume());
        assertNull(response.getRecentActivity().getLatestKnowledgeBaseQuestion());
    }

    @Test
    void getOverview_AllDataPresent_AssemblesOverview() {
        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder()
                .id(202L)
                .externalId(targetUserId)
                .username("owner")
                .build();
        when(userAccessService.requireVisibleUser(targetUserId, "owner")).thenReturn(targetUser);
        when(userResumeOverviewReadRepository.findAverageScoreByUserId(202L)).thenReturn(new BigDecimal("84.495"));
        when(userResumeOverviewReadRepository.findLatestResumeActivity(202L))
                .thenReturn(Optional.of(latestResume(
                        UUID.randomUUID(),
                        "resume.pdf",
                        ResumeStatus.PARSED.name(),
                        LocalDateTime.of(2026, 3, 29, 18, 0),
                        LocalDateTime.of(2026, 3, 29, 18, 2, 10),
                        AnalysisStatus.COMPLETED.name(),
                        LocalDateTime.of(2026, 3, 29, 18, 5, 40)
                )));
        when(userInterviewOverviewReadRepository.findAverageScoreByUserId(202L)).thenReturn(new BigDecimal("78.245"));
        when(userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(202L)).thenReturn(6L);
        when(userInterviewOverviewReadRepository.findLatestInterviewActivity(202L))
                .thenReturn(Optional.of(latestInterview(
                        UUID.randomUUID(),
                        "COMPLETED",
                        InterviewReportStatus.READY.name(),
                        new BigDecimal("81.5"),
                        LocalDateTime.of(2026, 3, 30, 10, 15)
                )));
        when(userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(202L))
                .thenReturn(Optional.of(latestQuestion(
                        UUID.randomUUID(),
                        "Java KB",
                        "Explain optimistic locking",
                        LocalDateTime.of(2026, 3, 30, 9, 40)
                )));

        UserOverviewResponse response = userOverviewQueryService.getOverview(targetUserId, "owner");

        assertEquals(new BigDecimal("84.50"), response.getResumeAverageScore());
        assertEquals(new BigDecimal("78.25"), response.getInterviewAverageScore());
        assertEquals(6L, response.getMockInterviewCompletedCount());
        assertTrue(response.getRecentActivity().getLatestResume().getParsed());
        assertTrue(response.getRecentActivity().getLatestResume().getAnalyzed());
        assertEquals("COMPLETED", response.getRecentActivity().getLatestInterview().getStatus());
        assertEquals("READY", response.getRecentActivity().getLatestInterview().getReportStatus());
        assertEquals(new BigDecimal("81.50"), response.getRecentActivity().getLatestInterview().getScore());
        assertEquals("Java KB", response.getRecentActivity().getLatestKnowledgeBaseQuestion().getKbName());
    }

    @Test
    void getOverview_MissingOptionalBranches_DoesNotPolluteOtherFields() {
        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder()
                .id(303L)
                .externalId(targetUserId)
                .username("owner")
                .build();
        when(userAccessService.requireVisibleUser(targetUserId, "owner")).thenReturn(targetUser);
        when(userResumeOverviewReadRepository.findAverageScoreByUserId(303L)).thenReturn(new BigDecimal("90"));
        when(userResumeOverviewReadRepository.findLatestResumeActivity(303L))
                .thenReturn(Optional.of(latestResume(
                        UUID.randomUUID(),
                        "resume.pdf",
                        ResumeStatus.PARSED.name(),
                        LocalDateTime.of(2026, 3, 29, 18, 0),
                        null,
                        AnalysisStatus.PENDING.name(),
                        null
                )));
        when(userInterviewOverviewReadRepository.findAverageScoreByUserId(303L)).thenReturn(null);
        when(userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(303L)).thenReturn(0L);
        when(userInterviewOverviewReadRepository.findLatestInterviewActivity(303L))
                .thenReturn(Optional.of(latestInterview(
                        UUID.randomUUID(),
                        "IN_PROGRESS",
                        null,
                        new BigDecimal("99.99"),
                        LocalDateTime.of(2026, 3, 30, 8, 0)
                )));
        when(userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(303L)).thenReturn(Optional.empty());

        UserOverviewResponse response = userOverviewQueryService.getOverview(targetUserId, "owner");

        assertEquals(new BigDecimal("90.00"), response.getResumeAverageScore());
        assertEquals(new BigDecimal("0.00"), response.getInterviewAverageScore());
        assertEquals(0L, response.getMockInterviewCompletedCount());
        assertTrue(response.getRecentActivity().getLatestResume().getParsed());
        assertFalse(response.getRecentActivity().getLatestResume().getAnalyzed());
        assertNull(response.getRecentActivity().getLatestResume().getParsedAt());
        assertEquals("NOT_READY", response.getRecentActivity().getLatestInterview().getReportStatus());
        assertNull(response.getRecentActivity().getLatestInterview().getScore());
        assertNull(response.getRecentActivity().getLatestKnowledgeBaseQuestion());
    }

    @Test
    void getOverview_ReadyInterviewWithoutOverallScore_KeepsLatestInterviewScoreNull() {
        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder()
                .id(404L)
                .externalId(targetUserId)
                .username("owner")
                .build();
        when(userAccessService.requireVisibleUser(targetUserId, "owner")).thenReturn(targetUser);
        when(userResumeOverviewReadRepository.findAverageScoreByUserId(404L)).thenReturn(null);
        when(userResumeOverviewReadRepository.findLatestResumeActivity(404L)).thenReturn(Optional.empty());
        when(userInterviewOverviewReadRepository.findAverageScoreByUserId(404L)).thenReturn(null);
        when(userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(404L)).thenReturn(1L);
        when(userInterviewOverviewReadRepository.findLatestInterviewActivity(404L))
                .thenReturn(Optional.of(latestInterview(
                        UUID.randomUUID(),
                        "COMPLETED",
                        InterviewReportStatus.READY.name(),
                        null,
                        LocalDateTime.of(2026, 3, 30, 11, 0)
                )));
        when(userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(404L)).thenReturn(Optional.empty());

        UserOverviewResponse response = userOverviewQueryService.getOverview(targetUserId, "owner");

        assertEquals("READY", response.getRecentActivity().getLatestInterview().getReportStatus());
        assertNull(response.getRecentActivity().getLatestInterview().getScore());
        assertEquals(new BigDecimal("0.00"), response.getInterviewAverageScore());
    }

    private UserResumeOverviewReadRepository.LatestResumeProjection latestResume(
            UUID resumeId,
            String fileName,
            String uploadStatus,
            LocalDateTime uploadedAt,
            LocalDateTime parsedAt,
            String analysisStatus,
            LocalDateTime analysisAt
    ) {
        return new UserResumeOverviewReadRepository.LatestResumeProjection() {
            @Override
            public UUID getResumeId() {
                return resumeId;
            }

            @Override
            public String getFileName() {
                return fileName;
            }

            @Override
            public String getUploadStatus() {
                return uploadStatus;
            }

            @Override
            public LocalDateTime getUploadedAt() {
                return uploadedAt;
            }

            @Override
            public LocalDateTime getParsedAt() {
                return parsedAt;
            }

            @Override
            public String getAnalysisStatus() {
                return analysisStatus;
            }

            @Override
            public LocalDateTime getAnalysisAt() {
                return analysisAt;
            }
        };
    }

    private UserInterviewOverviewReadRepository.LatestInterviewProjection latestInterview(
            UUID interviewId,
            String status,
            String reportStatus,
            BigDecimal score,
            LocalDateTime occurredAt
    ) {
        return new UserInterviewOverviewReadRepository.LatestInterviewProjection() {
            @Override
            public UUID getInterviewId() {
                return interviewId;
            }

            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public String getReportStatus() {
                return reportStatus;
            }

            @Override
            public BigDecimal getScore() {
                return score;
            }

            @Override
            public LocalDateTime getOccurredAt() {
                return occurredAt;
            }
        };
    }

    private UserKnowledgeBaseOverviewReadRepository.LatestKnowledgeBaseQuestionProjection latestQuestion(
            UUID kbId,
            String kbName,
            String question,
            LocalDateTime askedAt
    ) {
        return new UserKnowledgeBaseOverviewReadRepository.LatestKnowledgeBaseQuestionProjection() {
            @Override
            public UUID getKbId() {
                return kbId;
            }

            @Override
            public String getKbName() {
                return kbName;
            }

            @Override
            public String getQuestion() {
                return question;
            }

            @Override
            public LocalDateTime getAskedAt() {
                return askedAt;
            }
        };
    }
}
