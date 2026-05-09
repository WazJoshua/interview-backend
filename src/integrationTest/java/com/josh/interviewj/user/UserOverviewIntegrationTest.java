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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class UserOverviewIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

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
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User viewer;
    private User admin;

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
        viewer = userRepository.save(User.builder()
                .username("overview-viewer-" + UUID.randomUUID())
                .email("overview-viewer-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());
        admin = userRepository.save(User.builder()
                .username("overview-admin-" + UUID.randomUUID())
                .email("overview-admin-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());
        admin.addRole("ADMIN");
        admin = userRepository.save(admin);
    }

    @Test
    void getOverview_SelfAccess_ReturnsAggregatedOverview() throws Exception {
        Resume resume = resumeRepository.save(Resume.builder()
                .userId(owner.getId())
                .fileName("resume.pdf")
                .fileUrl("mock://resume.pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .parsedAt(LocalDateTime.of(2026, 3, 29, 18, 2, 10))
                .build());
        resumeAnalysisReportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(resume.getId())
                .userId(owner.getId())
                .completenessScore(84)
                .clarityScore(84)
                .overallScore(84)
                .status(AnalysisStatus.COMPLETED)
                .completedAt(LocalDateTime.of(2026, 3, 29, 18, 5, 40))
                .build());

        UUID interviewId = UUID.randomUUID();
        UUID interviewChatId = UUID.randomUUID();
        chatSessionRepository.save(ChatSession.builder()
                .externalId(interviewChatId)
                .userId(owner.getId())
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(interviewId)
                .lastMessageAt(LocalDateTime.of(2026, 3, 30, 10, 15))
                .build());
        InterviewSession interviewSession = interviewSessionRepository.save(InterviewSession.builder()
                .externalId(interviewId)
                .userId(owner.getId())
                .chatSessionId(interviewChatId)
                .status(InterviewStatus.COMPLETED)
                .startTime(LocalDateTime.of(2026, 3, 30, 9, 0))
                .endTime(LocalDateTime.of(2026, 3, 30, 10, 0))
                .build());
        interviewReportRepository.save(InterviewReport.builder()
                .sessionId(interviewSession.getId())
                .status(InterviewReportStatus.READY)
                .overallScore(new java.math.BigDecimal("81.50"))
                .build());

        KnowledgeBase knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Java KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());
        ChatSession kbSession = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(kbSession.getId())
                .role(ChatRole.USER)
                .messageType(ChatMessageType.TEXT)
                .content("Explain optimistic locking")
                .sequenceNumber(1)
                .createdAt(LocalDateTime.of(2026, 3, 30, 9, 40))
                .build());

        Authentication authentication = new UsernamePasswordAuthenticationToken(owner.getUsername(), "N/A");

        mockMvc.perform(get("/api/v1/users/{userId}/overview", owner.getExternalId()).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeAverageScore").value(84))
                .andExpect(jsonPath("$.data.interviewAverageScore").value(81.5))
                .andExpect(jsonPath("$.data.mockInterviewCompletedCount").value(1))
                .andExpect(jsonPath("$.data.recentActivity.latestInterview.reportStatus").value("READY"))
                .andExpect(jsonPath("$.data.recentActivity.latestInterview.score").value(81.5))
                .andExpect(jsonPath("$.data.recentActivity.latestResume.fileName").value("resume.pdf"))
                .andExpect(jsonPath("$.data.recentActivity.latestResume.parsed").value(true))
                .andExpect(jsonPath("$.data.recentActivity.latestResume.analysisStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.recentActivity.latestKnowledgeBaseQuestion.kbName").value("Java KB"))
                .andExpect(jsonPath("$.data.recentActivity.latestKnowledgeBaseQuestion.question").value("Explain optimistic locking"));
    }

    @Test
    void getOverview_AdminCanAccessOtherUser_Returns200() throws Exception {
        Authentication adminPrincipal = new UsernamePasswordAuthenticationToken(admin.getUsername(), "N/A");

        mockMvc.perform(get("/api/v1/users/{userId}/overview", owner.getExternalId()).principal(adminPrincipal))
                .andExpect(status().isOk());
    }

    @Test
    void getOverview_RegularUserAccessOtherUser_Returns404() throws Exception {
        Authentication viewerPrincipal = new UsernamePasswordAuthenticationToken(viewer.getUsername(), "N/A");

        mockMvc.perform(get("/api/v1/users/{userId}/overview", owner.getExternalId()).principal(viewerPrincipal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void getOverview_NoData_ReturnsEmptyStateWithExplicitNullBranches() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(owner.getUsername(), "N/A");

        mockMvc.perform(get("/api/v1/users/{userId}/overview", owner.getExternalId()).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeAverageScore").value(0))
                .andExpect(jsonPath("$.data.interviewAverageScore").value(0))
                .andExpect(jsonPath("$.data.mockInterviewCompletedCount").value(0))
                .andExpect(content().string(containsString("\"latestInterview\":null")))
                .andExpect(content().string(containsString("\"latestResume\":null")))
                .andExpect(content().string(containsString("\"latestKnowledgeBaseQuestion\":null")));
    }
}
