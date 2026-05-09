package com.josh.interviewj.controller;

import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.controller.ResumeController;
import com.josh.interviewj.resume.dto.response.ResumeDetailResponseDTO;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.josh.interviewj.resume.service.ResumeService;
import com.josh.interviewj.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ResumeController.class)
@AutoConfigureMockMvc(addFilters = false)
class ResumeControllerWebMvcTest {

    private static final JsonMapper TEST_OBJECT_MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResumeService resumeService;

    @MockitoBean
    private ResumeAnalysisService resumeAnalysisService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private UserDetailsService userDetailsService;

    /**
     * Resume detail endpoint should serialize parsedContent as nested business JSON.
     *
     * @throws Exception when the MVC call fails
     */
    @Test
    void getResumeDetail_WithParsedContent_ReturnsBusinessJsonShape() throws Exception {
        UUID resumeId = UUID.fromString("f8485340-f7cf-42d2-b202-1a87d2f3d905");
        ResumeDetailResponseDTO detail = ResumeDetailResponseDTO.builder()
                .id(resumeId)
                .fileName("resume.pdf")
                .fileType("application/pdf")
                .fileSize(2048L)
                .targetJob("Java Developer")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .hasAnalysis(false)
                .parsedContent(TEST_OBJECT_MAPPER.readTree("""
                        {"name":"Josh","skills":["Java"]}
                        """))
                .createdAt(LocalDateTime.of(2026, 3, 12, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 3, 12, 10, 5))
                .build();

        when(resumeService.getResumeDetail("testuser", resumeId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/resumes/{id}", resumeId)
                        .principal(new UsernamePasswordAuthenticationToken("testuser", "N/A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parsedContent.name").value("Josh"))
                .andExpect(jsonPath("$.data.parsedContent.skills[0]").value("Java"));
    }
}
