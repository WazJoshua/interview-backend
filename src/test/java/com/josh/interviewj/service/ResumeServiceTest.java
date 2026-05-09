package com.josh.interviewj.service;

import com.josh.interviewj.resume.dto.request.ResumeQueryDTO;
import com.josh.interviewj.resume.dto.response.ResumeResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.service.ResumeService;
import com.josh.interviewj.resume.service.ResumeStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ResumeStorageService resumeStorageService;

    @Mock
    private ResumeParseOutboxRepository resumeParseOutboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ResumeService resumeService;

    private User testUser;
    private Resume testResume;

    /**
     * Initialize common fixtures.
     */
    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        testResume = Resume.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .fileName("test.pdf")
                .fileType("application/pdf")
                .fileSize(1024L)
                .targetJob("Java Dev")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * List resumes should return mapped DTOs.
     */
    @Test
    void getResumes_Success() {
        ResumeQueryDTO query = new ResumeQueryDTO();
        query.setPage(0);
        query.setSize(10);
        query.setStatus("PARSED");

        Page<Resume> page = new PageImpl<>(List.of(testResume));

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Resume>>any(), any(Pageable.class)))
                .thenReturn(page);

        Page<ResumeResponseDTO> result = resumeService.getResumes("testuser", query);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        ResumeResponseDTO dto = result.getContent().get(0);
        assertEquals(testResume.getExternalId(), dto.getId());
        assertEquals("test.pdf", dto.getFileName());
        assertEquals(true, dto.getHasAnalysis());
    }

    /**
     * Upload should create resume record with UPLOADED status.
     */
    @Test
    void uploadResume_Success() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "resume content".getBytes()
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeStorageService.store(file)).thenReturn("uploads/resumes/resume.pdf");
        when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> {
            Resume resume = invocation.getArgument(0);
            resume.setId(2L);
            resume.setExternalId(UUID.randomUUID());
            resume.setCreatedAt(LocalDateTime.now());
            return resume;
        });

        ResumeUploadResponseDTO response = resumeService.uploadResume("testuser", file, "Java Developer");

        assertNotNull(response.getId());
        assertEquals("resume.pdf", response.getFileName());
        assertEquals("application/pdf", response.getFileType());
        assertEquals(ResumeStatus.UPLOADED, response.getStatus());
        verify(resumeParseOutboxRepository, never()).save(any());
    }

    @Test
    void triggerParse_StatusUploaded_UpdatesToPendingAndCreatesOutbox() {
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(2L)
                .externalId(resumeExternalId)
                .userId(testUser.getId())
                .status(ResumeStatus.UPLOADED)
                .build();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, testUser.getId()))
                .thenReturn(Optional.of(resume));
        when(resumeRepository.updateStatusWithCondition(2L, ResumeStatus.UPLOADED, ResumeStatus.PENDING)).thenReturn(1);

        ResumeParseResponseDTO response = resumeService.triggerParse("testuser", resumeExternalId);

        assertEquals(resumeExternalId, response.getId());
        assertEquals(ResumeStatus.PENDING, response.getStatus());
        verify(resumeParseOutboxRepository).save(any());
    }

    @Test
    void triggerParse_StatusParsing_ReturnsCurrentStatusAndDoesNotCreateOutbox() {
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(2L)
                .externalId(resumeExternalId)
                .userId(testUser.getId())
                .status(ResumeStatus.PARSING)
                .build();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, testUser.getId()))
                .thenReturn(Optional.of(resume));

        ResumeParseResponseDTO response = resumeService.triggerParse("testuser", resumeExternalId);

        assertEquals(ResumeStatus.PARSING, response.getStatus());
        verify(resumeRepository, never()).updateStatusWithCondition(anyLong(), any(), any());
        verify(resumeParseOutboxRepository, never()).save(any());
    }

    /**
     * Unsupported extension should be rejected.
     */
    @Test
    void uploadResume_UnsupportedExtension_ThrowsBusinessException() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.exe",
                "application/octet-stream",
                "bad".getBytes()
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resumeService.uploadResume("testuser", file, null)
        );

        assertEquals("RESUME_001", ex.getErrorCode());
    }

    /**
     * MIME mismatch should be rejected.
     */
    @Test
    void uploadResume_MimeTypeNotMatchedWithExtension_ThrowsBusinessException() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "text/plain",
                "bad mime".getBytes()
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resumeService.uploadResume("testuser", file, null)
        );

        assertEquals("RESUME_001", ex.getErrorCode());
    }

    /**
     * Oversized file should be rejected.
     */
    @Test
    void uploadResume_FileTooLarge_ThrowsBusinessException() {
        byte[] largeContent = new byte[(int) (5 * 1024 * 1024 + 1)];
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.md",
                "text/markdown",
                largeContent
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resumeService.uploadResume("testuser", file, null)
        );

        assertEquals("RESUME_002", ex.getErrorCode());
    }

    /**
     * Duplicate content should be rejected without persisting.
     */
    @Test
    void uploadResume_DuplicateContentHash_ThrowsBusinessException() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "resume content".getBytes()
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.existsByUserIdAndContentHashAndDeletedAtIsNull(eq(1L), any(String.class)))
                .thenReturn(true);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resumeService.uploadResume("testuser", file, null)
        );

        assertEquals("RESUME_006", ex.getErrorCode());
        verify(resumeStorageService, never()).store(any());
        verify(resumeRepository, never()).save(any());
    }

    /**
     * Delete should soft-delete and remove the stored file.
     */
    @Test
    void deleteResume_Success() {
        UUID resumeId = UUID.randomUUID();
        Resume storedResume = Resume.builder()
                .id(3L)
                .externalId(resumeId)
                .userId(1L)
                .fileUrl("uploads/resumes/test.pdf")
                .build();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeId, 1L))
                .thenReturn(Optional.of(storedResume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

        resumeService.deleteResume("testuser", resumeId);

        assertNotNull(storedResume.getDeletedAt());
        verify(resumeStorageService).delete("uploads/resumes/test.pdf");
        verify(resumeRepository).save(storedResume);
    }

    /**
     * Delete should return RESUME_005 when the resume does not exist.
     */
    @Test
    void deleteResume_NotFound_ThrowsBusinessException() {
        UUID resumeId = UUID.randomUUID();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeId, 1L))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> resumeService.deleteResume("testuser", resumeId)
        );

        assertEquals("RESUME_005", ex.getErrorCode());
    }
}
