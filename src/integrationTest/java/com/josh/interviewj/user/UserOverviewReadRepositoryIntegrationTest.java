package com.josh.interviewj.user;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatEventRepository;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.interview.model.InterviewReport;
import com.josh.interviewj.interview.model.InterviewReportStatus;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.repository.InterviewReportRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.user.repository.UserInterviewOverviewReadRepository;
import com.josh.interviewj.user.repository.UserKnowledgeBaseOverviewReadRepository;
import com.josh.interviewj.user.repository.UserResumeOverviewReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class UserOverviewReadRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeAnalysisReportRepository resumeAnalysisReportRepository;

    @Autowired
    private InterviewSessionRepository interviewSessionRepository;

    @Autowired
    private InterviewReportRepository interviewReportRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatEventRepository chatEventRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private UserResumeOverviewReadRepository userResumeOverviewReadRepository;

    @Autowired
    private UserInterviewOverviewReadRepository userInterviewOverviewReadRepository;

    @Autowired
    private UserKnowledgeBaseOverviewReadRepository userKnowledgeBaseOverviewReadRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User otherUser;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    chat_messages,
                    chat_events,
                    interview_reports,
                    interview_sessions,
                    chat_sessions,
                    resume_analysis_reports,
                    resumes,
                    knowledge_bases,
                    user_roles,
                    users
                RESTART IDENTITY CASCADE
                """);

        owner = userRepository.save(User.builder()
                .username("overview-owner-" + UUID.randomUUID())
                .email("overview-owner-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());
        otherUser = userRepository.save(User.builder()
                .username("overview-other-" + UUID.randomUUID())
                .email("overview-other-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());
    }

    @Test
    void findAverageScoreByUserId_FiltersCompletedReportsAndSoftDeletedResumes() {
        Resume includedResume = resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("included.pdf")
                .fileUrl("mock://included.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .build());
        Resume failedResume = resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("failed.pdf")
                .fileUrl("mock://failed.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.FAILED)
                .build());
        Resume deletedResume = resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("deleted.pdf")
                .fileUrl("mock://deleted.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .deletedAt(LocalDateTime.of(2026, 3, 30, 9, 0))
                .build());
        Resume otherUserResume = resumeRepository.save(Resume.builder()
                .userId(otherUser.getId())
                .fileName("other.pdf")
                .fileUrl("mock://other.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .build());

        resumeAnalysisReportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(includedResume.getId())
                .userId(owner.getId())
                .completenessScore(80)
                .clarityScore(80)
                .overallScore(80)
                .status(AnalysisStatus.COMPLETED)
                .completedAt(LocalDateTime.of(2026, 3, 29, 18, 5))
                .build());
        resumeAnalysisReportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(failedResume.getId())
                .userId(owner.getId())
                .completenessScore(10)
                .clarityScore(10)
                .overallScore(10)
                .status(AnalysisStatus.FAILED)
                .completedAt(LocalDateTime.of(2026, 3, 29, 18, 6))
                .build());
        resumeAnalysisReportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(deletedResume.getId())
                .userId(owner.getId())
                .completenessScore(100)
                .clarityScore(100)
                .overallScore(100)
                .status(AnalysisStatus.COMPLETED)
                .completedAt(LocalDateTime.of(2026, 3, 29, 18, 7))
                .build());
        resumeAnalysisReportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(otherUserResume.getId())
                .userId(otherUser.getId())
                .completenessScore(90)
                .clarityScore(90)
                .overallScore(90)
                .status(AnalysisStatus.COMPLETED)
                .completedAt(LocalDateTime.of(2026, 3, 29, 18, 8))
                .build());

        BigDecimal averageScore = userResumeOverviewReadRepository.findAverageScoreByUserId(owner.getId());

        assertEquals(new BigDecimal("80.0000000000000000"), averageScore);
    }

    @Test
    void findLatestResumeActivity_ExcludesSoftDeletedResumesAndOtherUsers() {
        Resume includedResume = resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("included.pdf")
                .fileUrl("mock://included.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .parsedAt(LocalDateTime.of(2026, 3, 29, 18, 2))
                .build());
        resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("deleted-latest.pdf")
                .fileUrl("mock://deleted-latest.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .deletedAt(LocalDateTime.of(2026, 3, 30, 9, 0))
                .build());
        resumeRepository.save(Resume.builder()
                .userId(otherUser.getId())
                .fileName("other-latest.pdf")
                .fileUrl("mock://other-latest.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .build());

        UserResumeOverviewReadRepository.LatestResumeProjection latest =
                userResumeOverviewReadRepository.findLatestResumeActivity(owner.getId()).orElseThrow();

        assertEquals(includedResume.getExternalId(), latest.getResumeId());
        assertEquals("included.pdf", latest.getFileName());
        assertEquals(ResumeStatus.PARSED.name(), latest.getUploadStatus());
        assertEquals(AnalysisStatus.PENDING.name(), latest.getAnalysisStatus());
    }

    @Test
    void interviewQueries_UseChatExternalIdFilterSoftDeleteAndReadyScore() {
        UUID includedInterviewId = UUID.randomUUID();
        UUID includedChatId = UUID.randomUUID();
        chatSessionRepository.save(ChatSession.builder()
                .externalId(includedChatId)
                .userId(owner.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(includedInterviewId)
                .lastMessageAt(LocalDateTime.of(2026, 3, 30, 10, 15))
                .build());
        InterviewSession includedSession = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(includedInterviewId)
                .userId(owner.getId())
                .chatSessionId(includedChatId)
                .status(InterviewStatus.COMPLETED)
                .startTime(LocalDateTime.of(2026, 3, 30, 9, 0))
                .endTime(LocalDateTime.of(2026, 3, 30, 10, 0))
                .build());
        interviewReportRepository.save(InterviewReport.builder()
                .sessionId(includedSession.getId())
                .status(InterviewReportStatus.READY)
                .overallScore(new BigDecimal("81.50"))
                .build());

        UUID deletedInterviewId = UUID.randomUUID();
        UUID deletedChatId = UUID.randomUUID();
        chatSessionRepository.save(ChatSession.builder()
                .externalId(deletedChatId)
                .userId(owner.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(deletedInterviewId)
                .lastMessageAt(LocalDateTime.of(2026, 3, 30, 11, 0))
                .build());
        InterviewSession deletedSession = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(deletedInterviewId)
                .userId(owner.getId())
                .chatSessionId(deletedChatId)
                .status(InterviewStatus.COMPLETED)
                .deletedAt(LocalDateTime.of(2026, 3, 30, 11, 30))
                .build());
        interviewReportRepository.save(InterviewReport.builder()
                .sessionId(deletedSession.getId())
                .status(InterviewReportStatus.READY)
                .overallScore(new BigDecimal("99.99"))
                .build());

        UUID notReadyInterviewId = UUID.randomUUID();
        UUID notReadyChatId = UUID.randomUUID();
        chatSessionRepository.save(ChatSession.builder()
                .externalId(notReadyChatId)
                .userId(owner.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(notReadyInterviewId)
                .lastMessageAt(LocalDateTime.of(2026, 3, 30, 9, 30))
                .build());
        InterviewSession notReadySession = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(notReadyInterviewId)
                .userId(owner.getId())
                .chatSessionId(notReadyChatId)
                .status(InterviewStatus.COMPLETED)
                .build());
        interviewReportRepository.save(InterviewReport.builder()
                .sessionId(notReadySession.getId())
                .status(InterviewReportStatus.GENERATING)
                .overallScore(new BigDecimal("77.77"))
                .build());

        BigDecimal averageScore = userInterviewOverviewReadRepository.findAverageScoreByUserId(owner.getId());
        long completedCount = userInterviewOverviewReadRepository.countCompletedMockInterviewsByUserId(owner.getId());
        UserInterviewOverviewReadRepository.LatestInterviewProjection latest =
                userInterviewOverviewReadRepository.findLatestInterviewActivity(owner.getId()).orElseThrow();

        assertEquals(new BigDecimal("81.5000000000000000"), averageScore);
        assertEquals(2L, completedCount);
        assertEquals(includedInterviewId, latest.getInterviewId());
        assertEquals(InterviewReportStatus.READY.name(), latest.getReportStatus());
        assertEquals(new BigDecimal("81.50"), latest.getScore());
        assertEquals(LocalDateTime.of(2026, 3, 30, 10, 15), latest.getOccurredAt());
    }

    @Test
    void findLatestQuestionByUserId_UsesKnowledgeBaseExternalIdAndOnlyUserTextMessages() {
        KnowledgeBase includedKnowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Included KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());
        ChatSession includedSession = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(includedKnowledgeBase.getExternalId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(includedSession.getId())
                .role(ChatRole.USER)
                .messageType(ChatMessageType.TEXT)
                .content("Included question")
                .sequenceNumber(1)
                .createdAt(LocalDateTime.of(2026, 3, 30, 9, 40))
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(includedSession.getId())
                .role(ChatRole.ASSISTANT)
                .messageType(ChatMessageType.ANSWER)
                .content("Assistant answer")
                .sequenceNumber(2)
                .createdAt(LocalDateTime.of(2026, 3, 30, 10, 40))
                .build());

        KnowledgeBase deletedKnowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Deleted KB")
                .status(KnowledgeBaseStatus.DELETED)
                .build());
        ChatSession deletedSession = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(deletedKnowledgeBase.getExternalId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(deletedSession.getId())
                .role(ChatRole.USER)
                .messageType(ChatMessageType.TEXT)
                .content("Deleted kb question")
                .sequenceNumber(1)
                .createdAt(LocalDateTime.of(2026, 3, 30, 11, 0))
                .build());

        ChatSession otherUserSession = chatSessionRepository.save(ChatSession.builder()
                .userId(otherUser.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(includedKnowledgeBase.getExternalId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(otherUserSession.getId())
                .role(ChatRole.USER)
                .messageType(ChatMessageType.TEXT)
                .content("Other user question")
                .sequenceNumber(1)
                .createdAt(LocalDateTime.of(2026, 3, 30, 12, 0))
                .build());

        UserKnowledgeBaseOverviewReadRepository.LatestKnowledgeBaseQuestionProjection latest =
                userKnowledgeBaseOverviewReadRepository.findLatestQuestionByUserId(owner.getId()).orElseThrow();

        assertNotNull(latest);
        assertEquals(includedKnowledgeBase.getExternalId(), latest.getKbId());
        assertEquals("Included KB", latest.getKbName());
        assertEquals("Included question", latest.getQuestion());
        assertNotNull(latest.getAskedAt());
    }
}
