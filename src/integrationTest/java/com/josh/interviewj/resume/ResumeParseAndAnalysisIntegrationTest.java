package com.josh.interviewj.resume;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.resume.consumer.ResumeAnalysisConsumer;
import com.josh.interviewj.resume.consumer.ResumeParseConsumer;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.service.DocumentParserService;
import com.josh.interviewj.common.outbox.OutboxPublisherService;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.josh.interviewj.resume.service.ResumeAnalysisOutboxPublisherService;
import com.josh.interviewj.resume.service.ResumeService;
import com.josh.interviewj.resume.service.ResumeStorageService;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.resume.prompt.ResumeAnalysisPrompts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class ResumeParseAndAnalysisIntegrationTest extends IntegrationTestBase {

    private static final String PURPOSE_ANALYSIS = "analysis";
    private static final String PARSED_CONTENT_JSON = """
            {
              "personalInfo": {},
              "education": [],
              "workExperience": [],
              "skills": [],
              "projects": []
            }
            """;

    private static final String EVIDENCE_JSON = """
            {
              "personalInfo": {},
              "education": [],
              "workExperience": [],
              "skills": [],
              "projects": [],
              "evidenceQuality": {
                "completenessScore": 80,
                "extractionConfidence": 0.9,
                "gaps": []
              }
            }
            """;

    private static final String SCORING_JSON = """
            {
              "scores": {
                "completeness": 80,
                "clarity": 90,
                "overall": 0
              },
              "sectionAnalysis": [],
              "improvementSuggestions": [],
              "summary": "ok"
            }
            """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeAnalysisReportRepository reportRepository;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private ResumeAnalysisOutboxPublisherService resumeAnalysisOutboxPublisherService;

    @Autowired
    private ResumeParseConsumer resumeParseConsumer;

    @Autowired
    private ResumeAnalysisConsumer resumeAnalysisConsumer;

    @Autowired
    private ResumeParseOutboxRepository resumeParseOutboxRepository;

    @Autowired
    private ResumeAnalysisOutboxRepository resumeAnalysisOutboxRepository;

    @Autowired
    private CreditWalletRepository creditWalletRepository;

    @MockitoBean
    private ResumeStorageService resumeStorageService;

    @MockitoBean
    private DocumentParserService documentParserService;

    @MockitoBean
    private LLMService llmService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUpMocks() {
        stubPublishSuccess();
        when(resumeStorageService.store(any(MultipartFile.class))).thenReturn("mock://resume.pdf");
        when(resumeStorageService.getFilePath(anyString())).thenReturn(Path.of("mock.pdf"));
        when(documentParserService.extractText(any(Path.class), anyString()))
                .thenReturn("John Doe\njohn@example.com\nJava Developer\nExperience...");

        when(llmService.generateStructuredJson(anyString(), anyString())).thenReturn(PARSED_CONTENT_JSON);

        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0, LlmRequest.class);
            if (request == null || request.purpose() == null) {
                return new LlmResponse(PARSED_CONTENT_JSON, "mock", "mock");
            }
            if (PURPOSE_ANALYSIS.equals(request.purpose()) && ResumeAnalysisPrompts.STAGE_A_SYSTEM_PROMPT.equals(request.systemPrompt())) {
                return new LlmResponse(EVIDENCE_JSON, "mock", "mock");
            }
            if (PURPOSE_ANALYSIS.equals(request.purpose()) && ResumeAnalysisPrompts.STAGE_B_SYSTEM_PROMPT.equals(request.systemPrompt())) {
                return new LlmResponse(SCORING_JSON, "mock", "mock");
            }
            return new LlmResponse(PARSED_CONTENT_JSON, "mock", "mock");
        });
    }

    @Test
    void parseAndAnalysisFlow_CompletesSuccessfully() throws java.io.IOException {
        String username = "it-user-" + UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("password_hash")
                .build());
        creditWalletRepository.save(CreditWallet.builder()
                .userId(user.getId())
                .purchasedBalanceMicros(1_000_000L)
                .build());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "fake pdf".getBytes(StandardCharsets.UTF_8)
        );

        // 1) Upload: status should be UPLOADED and no parsing task is enqueued yet.
        ResumeUploadResponseDTO upload = resumeService.uploadResume(username, file, "Backend Engineer");
        Resume uploaded = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(upload.getId(), user.getId())
                .orElseThrow();
        assertEquals(ResumeStatus.UPLOADED, uploaded.getStatus());

        // 2) Trigger parse: UPLOADED -> PENDING, creates outbox in the same TX.
        ResumeParseResponseDTO parseTriggered = resumeService.triggerParse(username, upload.getId());
        assertEquals(upload.getId(), parseTriggered.getId());
        assertEquals(ResumeStatus.PENDING, parseTriggered.getStatus());

        // 3) Publish outbox -> stream; consumer should pick it up and complete parsing.
        outboxPublisherService.publishPendingOutboxMessages();
        resumeParseConsumer.onMessage(readLastParseMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        Resume parsed = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(upload.getId(), user.getId())
                .orElseThrow();
        assertEquals(ResumeStatus.PARSED, parsed.getStatus());
        assertNotNull(parsed.getParsedAt());
        assertNotNull(parsed.getRawText());
        assertTrue(parsed.getRawText().contains("John Doe"));
        assertNotNull(parsed.getParsedContent());
        assertTrue(parsed.getParsedContent().contains("\"personalInfo\""));
        java.time.LocalDateTime parsedAt = parsed.getParsedAt();

        // 4) Trigger analysis and wait for completion.
        Long reportId = resumeAnalysisService.triggerAnalysis(upload.getId(), user.getId());
        resumeAnalysisOutboxPublisherService.publishPendingOutboxMessages();
        resumeAnalysisConsumer.onMessage(readLastAnalysisMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        ResumeAnalysisReport report = reportRepository.findById(reportId).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, report.getStatus());
        assertNotNull(report.getSummary());

        Resume after = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(upload.getId(), user.getId())
                .orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, after.getAnalysisStatus());
        assertEquals(parsedAt, after.getParsedAt());
    }

    private ResumeParseMessage readLastParseMessage() {
        ResumeParseOutbox outbox = resumeParseOutboxRepository.findAll().stream()
                .max(java.util.Comparator.comparing(ResumeParseOutbox::getId))
                .orElseThrow();
        return new ResumeParseMessage(outbox.getResumeId(), outbox.getResumeExternalId(), outbox.getId());
    }

    private ResumeAnalysisMessage readLastAnalysisMessage() {
        ResumeAnalysisOutbox outbox = resumeAnalysisOutboxRepository.findAll().stream()
                .max(java.util.Comparator.comparing(ResumeAnalysisOutbox::getId))
                .orElseThrow();
        return new ResumeAnalysisMessage(outbox.getReportId(), outbox.getResumeId(), outbox.getResumeExternalId(), outbox.getId());
    }

    private void stubPublishSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
