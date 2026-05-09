package com.josh.interviewj.controller;

import com.josh.interviewj.resume.dto.request.ResumeJobMatchCreateRequestDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchCreateResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchDetailResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchListItemResponseDTO;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.controller.ResumeJobMatchController;
import com.josh.interviewj.resume.service.ResumeJobMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeJobMatchControllerTest {

    @Mock
    private ResumeJobMatchService resumeJobMatchService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ResumeJobMatchController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createMatchReport_ReturnsPending() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(User.builder().id(1L).username("testuser").build()));
        when(resumeJobMatchService.createMatchReport(eq(resumeId), eq(1L), any(ResumeJobMatchCreateRequestDTO.class)))
                .thenReturn(ResumeJobMatchCreateResponseDTO.builder().matchReportId(2001L).status(AnalysisStatus.PENDING).build());

        mockMvc.perform(post("/api/v1/resumes/{id}/matches", resumeId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobTitle\":\"Backend\",\"jobDescription\":\"JD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchReportId").value(2001))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void getMatchReport_NotFound_Returns404() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(User.builder().id(1L).username("testuser").build()));
        when(resumeJobMatchService.getMatchReport(999L, 1L))
                .thenThrow(new BusinessException("RESUME_012", "Match report not found"));

        mockMvc.perform(get("/api/v1/resume-matches/{id}", 999L).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("RESUME_012"));
    }

    @Test
    void listMatchReports_ReturnsPage() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(User.builder().id(1L).username("testuser").build()));

        ResumeJobMatchListItemResponseDTO item = ResumeJobMatchListItemResponseDTO.builder()
                .matchReportId(1L)
                .status(AnalysisStatus.COMPLETED)
                .contentLocale("en-US")
                .matchScore(80)
                .summary("ok")
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();
        Page<ResumeJobMatchListItemResponseDTO> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);

        when(resumeJobMatchService.listMatchReports(eq(resumeId), eq(1L), any(PageRequest.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/resumes/{id}/matches", resumeId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].matchReportId").value(1))
                .andExpect(jsonPath("$.data.content[0].contentLocale").value("en-US"));
    }

    @Test
    void deleteMatchReport_Returns204() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(User.builder().id(1L).username("testuser").build()));
        doNothing().when(resumeJobMatchService).deleteMatchReport(1L, 1L);

        mockMvc.perform(delete("/api/v1/resume-matches/{id}", 1L).principal(authentication))
                .andExpect(status().isNoContent());
    }

    @Test
    void getMatchReport_ReturnsDetail() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(User.builder().id(1L).username("testuser").build()));
        when(resumeJobMatchService.getMatchReport(1L, 1L))
                .thenReturn(ResumeJobMatchDetailResponseDTO.builder()
                        .matchReportId(1L)
                        .jobTitle("Backend")
                        .jobDescription("JD")
                        .status(AnalysisStatus.COMPLETED)
                        .contentLocale("zh-CN")
                        .matchScore(80)
                        .summary("ok")
                        .strengths(List.of("s1"))
                        .gaps(List.of("g1"))
                        .suggestions(List.of("a1"))
                        .build());

        mockMvc.perform(get("/api/v1/resume-matches/{id}", 1L).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobDescription").value("JD"))
                .andExpect(jsonPath("$.data.contentLocale").value("zh-CN"));
    }
}
