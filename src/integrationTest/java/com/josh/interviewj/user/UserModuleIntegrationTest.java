package com.josh.interviewj.user;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for auth/users locale contract and 404 visibility rules.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class UserModuleIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeAnalysisReportRepository analysisReportRepository;

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @BeforeEach
    void setUp() {
        analysisReportRepository.deleteAllInBatch();
        resumeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authMePatchProfileAndAnalysisSnapshot_WorkEndToEnd() throws Exception {
        User user = userRepository.save(User.builder()
                .username("locale-owner-" + UUID.randomUUID())
                .email("locale-owner-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .nickname("Owner")
                .locale("en-US")
                .timezone("Asia/Shanghai")
                .build());
        User otherUser = userRepository.save(User.builder()
                .username("locale-other-" + UUID.randomUUID())
                .email("locale-other-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .nickname("Other")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build());

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        user.getUsername(),
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
        UsernamePasswordAuthenticationToken authenticatedPrincipal =
                UsernamePasswordAuthenticationToken.authenticated(
                        user.getUsername(),
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        mockMvc.perform(get("/api/v1/auth/me").with(user(user.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locale").value("en-US"));

        mockMvc.perform(patch("/api/v1/users/{userId}", user.getExternalId())
                        .principal(authenticatedPrincipal)
                        .contentType("application/json")
                        .content("""
                                {
                                  "locale": "zh-CN",
                                  "timezone": "Asia/Shanghai"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locale").value("zh-CN"));

        mockMvc.perform(get("/api/v1/users/{userId}", otherUser.getExternalId())
                        .principal(authenticatedPrincipal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));

        Resume resume = resumeRepository.save(Resume.builder()
                .externalId(UUID.randomUUID())
                .userId(user.getId())
                .fileName("resume.pdf")
                .fileUrl("mock://resume.pdf")
                .fileType("application/pdf")
                .rawText("John Doe")
                .parsedContent("{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[]}")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .build());

        Long reportId = resumeAnalysisService.triggerAnalysis(resume.getExternalId(), user.getId());
        ResumeAnalysisReport report = analysisReportRepository.findById(reportId).orElseThrow();

        assertNotNull(report);
        assertEquals("zh-CN", report.getContentLocale());
    }
}
