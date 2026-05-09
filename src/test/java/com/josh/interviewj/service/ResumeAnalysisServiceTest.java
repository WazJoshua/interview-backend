package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.dto.response.ResumeAnalysisResponseDTO;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeAnalysisServiceTest {

    private static final String PURPOSE_ANALYSIS = "analysis";
    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeAnalysisReportRepository reportRepository;

    @Mock
    private ResumeAnalysisOutboxRepository outboxRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiOperationGateway aiOperationGateway;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private ResumeAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ResumeAnalysisService(
                resumeRepository,
                reportRepository,
                outboxRepository,
                userRepository,
                aiOperationGateway,
                objectMapper
        );
        lenient().when(aiOperationGateway.prepareOperation(any())).thenReturn(new BusinessOperationContext(
                "biz-1",
                1L,
                "RESUME_ANALYSIS_REPORT",
                "resume-1",
                "analysis",
                java.util.List.of("RESUME_CREDITS"),
                java.util.Map.of()
        ));
    }

    @Test
    void triggerAnalysis_ResumeNotParsed_ThrowsBusinessException() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSING)
                .analysisStatus(AnalysisStatus.PENDING)
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerAnalysis(resumeExternalId, userId));

        assertEquals("RESUME_009", ex.getErrorCode());
    }

    @Test
    void triggerAnalysis_ExistingAnalyzingReport_ReturnsExistingReportId() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(99L)
                .resumeId(10L)
                .userId(userId)
                .status(AnalysisStatus.ANALYZING)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(reportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.of(report));

        Long reportId = service.triggerAnalysis(resumeExternalId, userId);

        assertEquals(99L, reportId);
        verify(outboxRepository, never()).save(any(ResumeAnalysisOutbox.class));
        verify(reportRepository, never()).save(any(ResumeAnalysisReport.class));
    }

    @Test
    void triggerAnalysis_NewReport_SetsPendingAndPersistsOutbox() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).locale("en-US").build()));
        when(reportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.empty());
        when(reportRepository.save(any(ResumeAnalysisReport.class))).thenAnswer(inv -> {
            ResumeAnalysisReport saved = inv.getArgument(0);
            saved.setId(123L);
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));

        Long reportId = service.triggerAnalysis(resumeExternalId, userId);

        assertEquals(123L, reportId);
        assertEquals(AnalysisStatus.PENDING, resume.getAnalysisStatus());
        ArgumentCaptor<ResumeAnalysisReport> reportCaptor = ArgumentCaptor.forClass(ResumeAnalysisReport.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertEquals(AnalysisStatus.PENDING, reportCaptor.getValue().getStatus());
        assertEquals("en-US", reportCaptor.getValue().getContentLocale());
        ArgumentCaptor<ResumeAnalysisOutbox> outboxCaptor = ArgumentCaptor.forClass(ResumeAnalysisOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals(savedReportId(reportCaptor), outboxCaptor.getValue().getReportId());
        assertEquals(resume.getId(), outboxCaptor.getValue().getResumeId());
        assertEquals(resume.getExternalId(), outboxCaptor.getValue().getResumeExternalId());
    }

    @Test
    void performAnalysis_PersistsEvidenceAndComputesOverall() {
        Long reportId = 77L;
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(1L)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .rawText("A".repeat(20))
                .parsedContent("{\"personalInfo\":{}}")
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(10L)
                .userId(1L)
                .contentLocale("zh-CN")
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        String evidenceJson = "{" +
                "\"personalInfo\":{}," +
                "\"education\":[]," +
                "\"workExperience\":[]," +
                "\"skills\":[]," +
                "\"projects\":[]," +
                "\"evidenceQuality\":{}" +
                "}";
        String scoringJson = "{" +
                "\"scores\":{\"completeness\":80,\"clarity\":90,\"overall\":0}," +
                "\"summary\":\"ok\"," +
                "\"improvementSuggestions\":[]," +
                "\"sectionAnalysis\":[]" +
                "}";

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report), Optional.of(report));
        when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume), Optional.of(resume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any(ResumeAnalysisReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(
                        AiInvocationResult.fromChat(new LlmResponse(
                                evidenceJson,
                                "default",
                                "qwen3.5-35b-a3b",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        )),
                        AiInvocationResult.fromChat(new LlmResponse(
                                scoringJson,
                                "default",
                                "qwen3.5-35b-a3b",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        ))
                );

        service.performAnalysis(reportId);

        ArgumentCaptor<AiInvocationInput> requestCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway, org.mockito.Mockito.times(2)).executeInvocation(any(), any(), requestCaptor.capture());
        assertTrue(requestCaptor.getAllValues().get(1).userPrompt().contains("Simplified Chinese"));

        assertEquals(80, report.getCompletenessScore());
        assertEquals(90, report.getClarityScore());
        assertEquals(85, report.getOverallScore());
        assertEquals(AnalysisStatus.COMPLETED, report.getStatus());
        assertNotNull(report.getEvidenceJson());
        verify(reportRepository).save(report);
    }

    @Test
    void performAnalysis_WhenStageBFails_RecordsStageAAsNonChargeableFailure() {
        Long reportId = 90L;
        Resume resume = Resume.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .rawText("resume")
                .parsedContent("{}")
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(11L)
                .userId(1L)
                .contentLocale("en-US")
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        String evidenceJson = "{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}";
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report), Optional.of(report));
        when(resumeRepository.findById(11L)).thenReturn(Optional.of(resume));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(AiInvocationResult.fromChat(new LlmResponse(
                        evidenceJson,
                        "default",
                        "qwen3.5-35b-a3b",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                )))
                .thenThrow(new RuntimeException("stage b failed"));

        assertThrows(RuntimeException.class, () -> service.executeAnalysisAndFinalize(reportId));

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), eq(com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome.SUCCESS), eq(null));
    }

    @Test
    void performAnalysis_ClampsScoresAndComputesOverall() {
        Long reportId = 88L;
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(11L)
                .externalId(resumeExternalId)
                .userId(1L)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .rawText("resume")
                .parsedContent("{}")
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(11L)
                .userId(1L)
                .contentLocale("en-US")
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        String evidenceJson = "{" +
                "\"personalInfo\":{}," +
                "\"education\":[]," +
                "\"workExperience\":[]," +
                "\"skills\":[]," +
                "\"projects\":[]," +
                "\"evidenceQuality\":{}" +
                "}";
        String scoringJson = "{" +
                "\"scores\":{\"completeness\":120,\"clarity\":-5,\"overall\":0}," +
                "\"summary\":\"ok\"," +
                "\"improvementSuggestions\":[]," +
                "\"sectionAnalysis\":[]" +
                "}";

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report), Optional.of(report));
        when(resumeRepository.findById(11L)).thenReturn(Optional.of(resume), Optional.of(resume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any(ResumeAnalysisReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(
                        AiInvocationResult.fromChat(new LlmResponse(
                                evidenceJson,
                                "default",
                                "qwen3.5-35b-a3b",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        )),
                        AiInvocationResult.fromChat(new LlmResponse(
                                scoringJson,
                                "default",
                                "qwen3.5-35b-a3b",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        ))
                );

        service.performAnalysis(reportId);

        assertEquals(100, report.getCompletenessScore());
        assertEquals(0, report.getClarityScore());
        assertEquals(50, report.getOverallScore());
        assertEquals(AnalysisStatus.COMPLETED, report.getStatus());
    }

    @Test
    void performAnalysis_HistoricalNullContentLocale_NormalizesToDefaultAndPersists() {
        Long reportId = 66L;
        Resume resume = Resume.builder()
                .id(15L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .rawText("resume")
                .parsedContent("{}")
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(15L)
                .userId(1L)
                .contentLocale(null)
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        String evidenceJson = "{\"personalInfo\":{},\"education\":[],\"workExperience\":[],\"skills\":[],\"projects\":[],\"evidenceQuality\":{}}";
        String scoringJson = "{\"scores\":{\"completeness\":80,\"clarity\":90,\"overall\":0},\"summary\":\"ok\",\"improvementSuggestions\":[],\"sectionAnalysis\":[]}";

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report), Optional.of(report), Optional.of(report));
        when(resumeRepository.findById(15L)).thenReturn(Optional.of(resume), Optional.of(resume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any(ResumeAnalysisReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiOperationGateway.executeInvocation(any(), any(), any()))
                .thenReturn(
                        AiInvocationResult.fromChat(new LlmResponse(
                                evidenceJson,
                                "default",
                                "stage-a",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        )),
                        AiInvocationResult.fromChat(new LlmResponse(
                                scoringJson,
                                "default",
                                "stage-b",
                                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                        ))
                );

        service.performAnalysis(reportId);

        assertEquals("zh-CN", report.getContentLocale());
    }

    @Test
    void handleRetryableFailure_IncrementsRetryCountAndCreatesNewOutbox() {
        Long reportId = 91L;
        Long sourceOutboxId = 301L;
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(12L)
                .externalId(resumeExternalId)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(12L)
                .userId(1L)
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(resumeRepository.findById(12L)).thenReturn(Optional.of(resume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.existsByRetrySourceOutboxId(sourceOutboxId)).thenReturn(false);

        service.handleRetryableFailure(reportId, "retry-me", sourceOutboxId);

        assertEquals(AnalysisStatus.PENDING, report.getStatus());
        assertEquals(1, report.getRetryCount());
        assertEquals("retry-me", report.getErrorMessage());
        assertEquals(AnalysisStatus.PENDING, resume.getAnalysisStatus());
        ArgumentCaptor<ResumeAnalysisOutbox> captor = ArgumentCaptor.forClass(ResumeAnalysisOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals(sourceOutboxId, captor.getValue().getRetrySourceOutboxId());
    }

    @Test
    void handleRetryableFailure_WhenRetrySourceAlreadyExists_DoesNotCreateSecondOutbox() {
        Long reportId = 191L;
        Long sourceOutboxId = 401L;
        UUID resumeExternalId = UUID.randomUUID();
        Resume resume = Resume.builder()
                .id(22L)
                .externalId(resumeExternalId)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .errorMessage(null)
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(22L)
                .userId(1L)
                .status(AnalysisStatus.ANALYZING)
                .retryCount(0)
                .errorMessage(null)
                .build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(resumeRepository.findById(22L)).thenReturn(Optional.of(resume));
        when(outboxRepository.existsByRetrySourceOutboxId(sourceOutboxId)).thenReturn(true);

        service.handleRetryableFailure(reportId, "retry-me", sourceOutboxId);

        assertEquals(AnalysisStatus.ANALYZING, report.getStatus());
        assertEquals(0, report.getRetryCount());
        assertEquals(AnalysisStatus.ANALYZING, resume.getAnalysisStatus());
        assertEquals(null, report.getErrorMessage());
        assertEquals(null, resume.getErrorMessage());
        verify(reportRepository, never()).save(any(ResumeAnalysisReport.class));
        verify(resumeRepository, never()).save(any(Resume.class));
        verify(outboxRepository, never()).save(any(ResumeAnalysisOutbox.class));
    }

    @Test
    void handleTerminalFailure_MarksFailedWithoutCreatingOutbox() {
        Long reportId = 92L;
        Resume resume = Resume.builder()
                .id(13L)
                .analysisStatus(AnalysisStatus.ANALYZING)
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(13L)
                .userId(1L)
                .status(AnalysisStatus.ANALYZING)
                .retryCount(2)
                .build();

        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(resumeRepository.findById(13L)).thenReturn(Optional.of(resume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleTerminalFailure(reportId, "terminal");

        assertEquals(AnalysisStatus.FAILED, report.getStatus());
        assertEquals("terminal", report.getErrorMessage());
        assertEquals(AnalysisStatus.FAILED, resume.getAnalysisStatus());
        verify(outboxRepository, never()).save(any(ResumeAnalysisOutbox.class));
    }

    @Test
    void getAnalysisReport_MapsResumeIdAsUuid() {
        UUID resumeExternalId = UUID.randomUUID();
        Long userId = 1L;
        Resume resume = Resume.builder()
                .id(10L)
                .externalId(resumeExternalId)
                .userId(userId)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .build();
        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(55L)
                .resumeId(10L)
                .userId(userId)
                .contentLocale("en-US")
                .status(AnalysisStatus.COMPLETED)
                .completenessScore(80)
                .clarityScore(90)
                .overallScore(85)
                .summary("ok")
                .improvementSuggestionsJson("[]")
                .sectionAnalysisJson("[]")
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();

        when(resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeExternalId, userId))
                .thenReturn(Optional.of(resume));
        when(reportRepository.findByResumeIdAndUserId(10L, userId)).thenReturn(Optional.of(report));

        ResumeAnalysisResponseDTO dto = service.getAnalysisReport(resumeExternalId, userId);

        assertNotNull(dto);
        assertEquals(resumeExternalId, dto.getResumeId());
        assertEquals(55L, dto.getReportId());
        assertEquals("en-US", dto.getContentLocale());
        assertNotNull(dto.getScores());
        assertEquals(85, dto.getScores().getOverall());
    }

    /**
     * Extracts the saved report id from the captured report argument.
     *
     * @param reportCaptor report captor
     * @return persisted report id
     */
    private Long savedReportId(ArgumentCaptor<ResumeAnalysisReport> reportCaptor) {
        return reportCaptor.getValue().getId();
    }
}
