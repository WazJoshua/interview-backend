package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.dto.request.ResumeJobMatchCreateRequestDTO;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeJobMatchReport;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeJobMatchReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.resume.service.ResumeJobMatchService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeJobMatchServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeAnalysisReportRepository analysisReportRepository;

    @Mock
    private ResumeJobMatchReportRepository matchReportRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiOperationGateway aiOperationGateway;

    @Mock
    private ExecutorService virtualThreadExecutor;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private ResumeJobMatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        service = new ResumeJobMatchService(
                resumeRepository,
                analysisReportRepository,
                matchReportRepository,
                userRepository,
                aiOperationGateway,
                JsonMapper.builder().build(),
                virtualThreadExecutor,
                transactionManager
        );
    }

    @Test
    void createMatchReport_AnalysisNotCompleted_ThrowsResume011() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.empty());

        ResumeJobMatchCreateRequestDTO request = new ResumeJobMatchCreateRequestDTO();
        request.setJobTitle("Backend");
        request.setJobDescription("JD");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMatchReport(resumeExternalId, userId, request));
        assertEquals("RESUME_011", ex.getErrorCode());
    }

    @Test
    void createMatchReport_EvidenceMissing_ThrowsResume014() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();
        ResumeAnalysisReport analysisReport = ResumeAnalysisReport.builder()
                .id(1L)
                .resumeId(10L)
                .userId(userId)
                .status(AnalysisStatus.COMPLETED)
                .evidenceJson(null)
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.of(analysisReport));

        ResumeJobMatchCreateRequestDTO request = new ResumeJobMatchCreateRequestDTO();
        request.setJobTitle("Backend");
        request.setJobDescription("JD");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMatchReport(resumeExternalId, userId, request));
        assertEquals("RESUME_014", ex.getErrorCode());
    }

    @Test
    void createMatchReport_Success_SchedulesAsync() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();
        ResumeAnalysisReport analysisReport = ResumeAnalysisReport.builder()
                .id(1L)
                .resumeId(10L)
                .userId(userId)
                .status(AnalysisStatus.COMPLETED)
                .evidenceJson("{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}")
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.of(analysisReport));
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).locale("en-US").build()));
        when(matchReportRepository.save(any(ResumeJobMatchReport.class))).thenAnswer(invocation -> {
            ResumeJobMatchReport saved = invocation.getArgument(0);
            saved.setId(123L);
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        ResumeJobMatchCreateRequestDTO request = new ResumeJobMatchCreateRequestDTO();
        request.setJobTitle("Backend");
        request.setJobDescription("JD");

        var response = service.createMatchReport(resumeExternalId, userId, request);
        assertEquals(123L, response.getMatchReportId());
        assertEquals(AnalysisStatus.PENDING, response.getStatus());
        ArgumentCaptor<ResumeJobMatchReport> reportCaptor = ArgumentCaptor.forClass(ResumeJobMatchReport.class);
        verify(matchReportRepository).save(reportCaptor.capture());
        assertEquals("en-US", reportCaptor.getValue().getContentLocale());
        verify(virtualThreadExecutor).submit(any(Runnable.class));
    }

    @Test
    void performMatch_Success_PersistsCompleted() throws Exception {
        Long matchReportId = 10L;
        Long userId = 1L;
        Long resumeId = 99L;

        ResumeJobMatchReport matchReport = ResumeJobMatchReport.builder()
                .id(matchReportId)
                .resumeId(resumeId)
                .userId(userId)
                .contentLocale("en-US")
                .jobTitle("Backend")
                .jobDescription("JD")
                .status(AnalysisStatus.PENDING)
                .build();
        Resume resume = Resume.builder()
                .id(resumeId)
                .externalId(UUID.randomUUID())
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();
        ResumeAnalysisReport analysisReport = ResumeAnalysisReport.builder()
                .id(1L)
                .resumeId(resumeId)
                .userId(userId)
                .status(AnalysisStatus.COMPLETED)
                .evidenceJson("{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}")
                .build();

        when(matchReportRepository.findById(matchReportId)).thenReturn(Optional.of(matchReport));
        when(matchReportRepository.updateStatus(anyLong(), any(AnalysisStatus.class))).thenReturn(1);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(resumeId, userId)).thenReturn(Optional.of(analysisReport));
        when(matchReportRepository.save(any(ResumeJobMatchReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String outputJson = "{\"matchScore\":88,\"summary\":\"ok\",\"strengths\":[\"s1\"],\"gaps\":[\"g1\"],\"suggestions\":[\"a1\"]}";
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                userId,
                "RESUME",
                resume.getExternalId().toString(),
                "analysis",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(
                new LlmResponse(
                        outputJson,
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )
        ));

        service.performMatch(matchReportId);

        ArgumentCaptor<ResumeJobMatchReport> captor = ArgumentCaptor.forClass(ResumeJobMatchReport.class);
        verify(matchReportRepository).save(captor.capture());
        ResumeJobMatchReport saved = captor.getValue();
        assertEquals(AnalysisStatus.COMPLETED, saved.getStatus());
        assertEquals("en-US", saved.getContentLocale());
        assertEquals(88, saved.getMatchScore());
        assertNotNull(saved.getCompletedAt());
    }

    @Test
    void performMatch_Success_SubmitsGatewayOutcome() throws Exception {
        Long matchReportId = 15L;
        Long userId = 1L;
        Long resumeId = 99L;
        UUID resumeExternalId = UUID.randomUUID();

        ResumeJobMatchReport matchReport = ResumeJobMatchReport.builder()
                .id(matchReportId)
                .resumeId(resumeId)
                .userId(userId)
                .contentLocale("en-US")
                .jobTitle("Backend")
                .jobDescription("JD")
                .status(AnalysisStatus.PENDING)
                .build();
        Resume resume = Resume.builder()
                .id(resumeId)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();
        ResumeAnalysisReport analysisReport = ResumeAnalysisReport.builder()
                .id(1L)
                .resumeId(resumeId)
                .userId(userId)
                .status(AnalysisStatus.COMPLETED)
                .evidenceJson("{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}")
                .build();
        when(matchReportRepository.findById(matchReportId)).thenReturn(Optional.of(matchReport));
        when(matchReportRepository.updateStatus(anyLong(), any(AnalysisStatus.class))).thenReturn(1);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(resumeId, userId)).thenReturn(Optional.of(analysisReport));
        when(matchReportRepository.save(any(ResumeJobMatchReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                userId,
                "RESUME",
                resumeExternalId.toString(),
                "analysis",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(
                new LlmResponse(
                        "{\"matchScore\":88,\"summary\":\"ok\",\"strengths\":[\"s1\"],\"gaps\":[\"g1\"],\"suggestions\":[\"a1\"]}",
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )
        ));

        service.performMatch(matchReportId);

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void performMatch_HistoricalNullContentLocale_NormalizesToDefault() throws Exception {
        Long matchReportId = 21L;
        Long userId = 1L;
        Long resumeId = 33L;

        ResumeJobMatchReport matchReport = ResumeJobMatchReport.builder()
                .id(matchReportId)
                .resumeId(resumeId)
                .userId(userId)
                .contentLocale(null)
                .jobTitle("Backend")
                .jobDescription("JD")
                .status(AnalysisStatus.PENDING)
                .build();
        Resume resume = Resume.builder()
                .id(resumeId)
                .externalId(UUID.randomUUID())
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .build();
        ResumeAnalysisReport analysisReport = ResumeAnalysisReport.builder()
                .id(1L)
                .resumeId(resumeId)
                .userId(userId)
                .status(AnalysisStatus.COMPLETED)
                .evidenceJson("{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}")
                .build();

        when(matchReportRepository.findById(matchReportId)).thenReturn(Optional.of(matchReport), Optional.of(matchReport), Optional.of(matchReport));
        when(matchReportRepository.updateStatus(anyLong(), any(AnalysisStatus.class))).thenReturn(1);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume));
        when(analysisReportRepository.findByResumeIdAndUserId(resumeId, userId)).thenReturn(Optional.of(analysisReport));
        when(matchReportRepository.save(any(ResumeJobMatchReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                userId,
                "RESUME",
                resume.getExternalId().toString(),
                "analysis",
                List.of("RESUME_CREDITS"),
                Map.of()
        ));
        when(aiOperationGateway.executeInvocation(any(), any(), any())).thenReturn(AiInvocationResult.fromChat(
                new LlmResponse(
                        "{\"matchScore\":88,\"summary\":\"ok\",\"strengths\":[\"s1\"],\"gaps\":[\"g1\"],\"suggestions\":[\"a1\"]}",
                        "mock",
                        "mock",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )
        ));

        service.performMatch(matchReportId);

        assertEquals("zh-CN", matchReport.getContentLocale());
    }
}
