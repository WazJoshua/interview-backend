package com.josh.interviewj.controller;

import com.josh.interviewj.resume.dto.request.ResumeQueryDTO;
import com.josh.interviewj.resume.dto.response.ResumeDetailResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeAnalysisResponseDTO;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.controller.ResumeController;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.josh.interviewj.resume.service.ResumeService;
import com.josh.interviewj.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResumeControllerTest {

    @Mock
    private ResumeService resumeService;

    @Mock
    private ResumeAnalysisService resumeAnalysisService;

    @Mock
    private UserRepository userRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ResumeController resumeController;

    private MockMvc mockMvc;

    /**
     * Build MockMvc with controller advice.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(resumeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Upload should return 201 with Location and UPLOADED status.
     */
    @Test
    void uploadResume_ValidRequest_ReturnsCreated() throws Exception {
        UUID resumeId = UUID.fromString("e5f6a7b8-c9d0-1234-ef01-234567890abc");
        ResumeUploadResponseDTO responseDTO = ResumeUploadResponseDTO.builder()
                .id(resumeId)
                .fileName("resume.pdf")
                .fileType("application/pdf")
                .fileSize(2048L)
                .targetJob("Java Developer")
                .status(ResumeStatus.UPLOADED)
                .createdAt(LocalDateTime.now())
                .build();

        when(resumeService.uploadResume(any(), any(), any())).thenReturn(responseDTO);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(file)
                        .param("targetJob", "Java Developer")
                .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.message").value("Resume uploaded successfully"))
                .andExpect(header().string("Location", "/api/v1/resumes/" + resumeId))
                .andExpect(jsonPath("$.data.fileName").value("resume.pdf"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"));
    }

    /**
     * Detail endpoint should return 200 with resume fields.
     */
    @Test
    void getResumeDetail_ValidRequest_ReturnsSuccessResponse() throws Exception {
        UUID resumeId = UUID.randomUUID();
        ResumeDetailResponseDTO detail = ResumeDetailResponseDTO.builder()
                .id(resumeId)
                .fileName("resume.pdf")
                .fileType("application/pdf")
                .fileSize(2048L)
                .targetJob("Java Developer")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .hasAnalysis(false)
                .parsedContent(null)
                .errorMessage(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(resumeService.getResumeDetail(eq("testuser"), eq(resumeId))).thenReturn(detail);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/resumes/{id}", resumeId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.fileName").value("resume.pdf"))
                .andExpect(jsonPath("$.data.status").value("PARSED"));
    }

    /**
     * Trigger parse should return 200 and current status.
     */
    @Test
    void triggerParse_Uploaded_ReturnsPending() throws Exception {
        UUID resumeId = UUID.randomUUID();
        ResumeParseResponseDTO response = ResumeParseResponseDTO.builder()
                .id(resumeId)
                .status(ResumeStatus.PENDING)
                .build();

        when(resumeService.triggerParse(eq("testuser"), eq(resumeId))).thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/resumes/{id}/parse", resumeId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(resumeId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /**
     * Trigger parse should map not-found to 404.
     */
    @Test
    void triggerParse_ServiceThrowsResume005_Returns404() throws Exception {
        UUID resumeId = UUID.randomUUID();
        when(resumeService.triggerParse(eq("testuser"), eq(resumeId)))
                .thenThrow(new BusinessException("RESUME_005", "Resume not found"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/resumes/{id}/parse", resumeId).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Resume not found"))
                .andExpect(jsonPath("$.error.type").value("RESUME_005"));
    }

    /**
     * Upload should map business exception to 400.
     */
    @Test
    void uploadResume_ServiceThrowsBusinessException_ReturnsBadRequest() throws Exception {
        when(resumeService.uploadResume(any(), any(), any()))
                .thenThrow(new BusinessException("RESUME_001", "Unsupported file format"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "resume.exe", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Unsupported file format"))
                .andExpect(jsonPath("$.error.type").value("RESUME_001"));
    }

    /**
     * Duplicate hash should map to 409 conflict.
     */
    @Test
    void uploadResume_DuplicateContentHash_ReturnsConflict() throws Exception {
        when(resumeService.uploadResume(any(), any(), any()))
                .thenThrow(new BusinessException("RESUME_006", "Duplicate resume content is not allowed"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "resume-copy.pdf", "application/pdf", "x".getBytes());

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Duplicate resume content is not allowed"))
                .andExpect(jsonPath("$.error.type").value("RESUME_006"));
    }

    /**
     * List endpoint should accept query params and map them to service call.
     */
    @Test
    void getResumes_WithQueryParams_ReturnsSuccessResponse() throws Exception {
        ResumeResponseDTO resume = ResumeResponseDTO.builder()
                .id(UUID.randomUUID())
                .fileName("resume.pdf")
                .fileType("application/pdf")
                .fileSize(2048L)
                .targetJob("Java Developer")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .hasAnalysis(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Page<ResumeResponseDTO> page = new PageImpl<>(List.of(resume), PageRequest.of(1, 5), 1);
        when(resumeService.getResumes(any(), any())).thenReturn(page);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/resumes")
                        .principal(authentication)
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "createdAt,asc")
                        .param("status", "PARSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.content[0].fileName").value("resume.pdf"))
                .andExpect(jsonPath("$.data.content[0].hasAnalysis").value(true));

        ArgumentCaptor<ResumeQueryDTO> captor = ArgumentCaptor.forClass(ResumeQueryDTO.class);
        verify(resumeService).getResumes(eq("testuser"), captor.capture());
        ResumeQueryDTO query = captor.getValue();
        assertEquals(1, query.getPage());
        assertEquals(5, query.getSize());
        assertEquals("createdAt,asc", query.getSort());
        assertEquals("PARSED", query.getStatus());
    }

    /**
     * List endpoint should use default query values when absent.
     */
    @Test
    void getResumes_WithoutQueryParams_UsesDefaultQueryValues() throws Exception {
        Page<ResumeResponseDTO> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(resumeService.getResumes(any(), any())).thenReturn(emptyPage);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/resumes").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(0));

        ArgumentCaptor<ResumeQueryDTO> captor = ArgumentCaptor.forClass(ResumeQueryDTO.class);
        verify(resumeService).getResumes(eq("testuser"), captor.capture());
        ResumeQueryDTO query = captor.getValue();
        assertEquals(0, query.getPage());
        assertEquals(20, query.getSize());
        assertEquals("createdAt,desc", query.getSort());
        assertNull(query.getStatus());
    }

    /**
     * List endpoint should map unknown exceptions to 500.
     */
    @Test
    void getResumes_ServiceThrowsException_ReturnsInternalServerError() throws Exception {
        when(resumeService.getResumes(any(), any())).thenThrow(new RuntimeException("db error"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/resumes").principal(authentication))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.error.type").value("INTERNAL_ERROR"));
    }

    /**
     * Delete endpoint should return 204 on success.
     */
    @Test
    void deleteResume_ValidRequest_ReturnsNoContent() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        doNothing().when(resumeService).deleteResume("testuser", resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId).principal(authentication))
                .andExpect(status().isNoContent());
    }

    /**
     * Delete endpoint should return 404 when resume not found.
     */
    @Test
    void deleteResume_NotFound_Returns404() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        doThrow(new BusinessException("RESUME_005", "简历不存在"))
                .when(resumeService).deleteResume("testuser", resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.error.type").value("RESUME_005"));
    }

    @Test
    void triggerAnalysis_EmptyBody_ReturnsReportId() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(User.builder().id(1L).username("testuser").build()));
        when(resumeAnalysisService.triggerAnalysis(resumeId, 1L)).thenReturn(123L);

        mockMvc.perform(post("/api/v1/resumes/{id}/analysis", resumeId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(123));
    }

    @Test
    void triggerAnalysis_LegacyFields_ReturnsBadRequest() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(User.builder().id(1L).username("testuser").build()));

        mockMvc.perform(post("/api/v1/resumes/{id}/analysis", resumeId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetJobTitle\":\"Backend\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("RESUME_015"));
    }

    @Test
    void getAnalysisReport_ResponseDoesNotIncludeMatchScore() throws Exception {
        UUID resumeId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        when(userRepository.findByUsername("testuser")).thenReturn(java.util.Optional.of(User.builder().id(1L).username("testuser").build()));
        ResumeAnalysisResponseDTO response = ResumeAnalysisResponseDTO.builder()
                .reportId(1001L)
                .resumeId(resumeId)
                .status(AnalysisStatus.COMPLETED)
                .contentLocale("zh-CN")
                .scores(ResumeAnalysisResponseDTO.Scores.builder()
                        .completeness(80)
                        .clarity(90)
                        .overall(85)
                        .build())
                .summary("ok")
                .build();
        when(resumeAnalysisService.getAnalysisReport(resumeId, 1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/resumes/{id}/analysis", resumeId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentLocale").value("zh-CN"))
                .andExpect(jsonPath("$.data.scores.match").doesNotExist());
    }
}
