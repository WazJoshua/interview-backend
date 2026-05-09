package com.josh.interviewj.resume;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.resume.consumer.ResumeAnalysisConsumer;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.prompt.ResumeAnalysisPrompts;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisOutboxPublisherService;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import com.josh.interviewj.usage.repository.UsageRejectionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the resume analysis outbox, stream, retry, and recovery pipeline.
 */
@SpringBootTest
class ResumeAnalysisPipelineIntegrationTest extends IntegrationTestBase {

    private static final String PURPOSE_ANALYSIS = "analysis";
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
    private ResumeAnalysisOutboxRepository outboxRepository;

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private ResumeAnalysisOutboxPublisherService publisherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CreditWalletRepository creditWalletRepository;

    @Autowired
    private UsageRejectionRecordRepository usageRejectionRecordRepository;

    @Autowired
    private ResumeAnalysisConsumer resumeAnalysisConsumer;

    @MockitoBean
    private LLMService llmService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /**
     * Clears data and stream state before each scenario.
     */
    @BeforeEach
    void setUp() {
        stubPublishSuccess();
        outboxRepository.deleteAllInBatch();
        reportRepository.deleteAllInBatch();
        resumeRepository.deleteAllInBatch();
        usageRejectionRecordRepository.deleteAllInBatch();
        creditWalletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        resetResumeAnalysisRedisState();
    }

    /**
     * Verifies the happy path from outbox publication to completed analysis.
     */
    @Test
    void publishThenConsume_HappyPath_CompletesReportAndWritesIdempotentKey() throws java.io.IOException {
        mockSuccessfulAnalysis();
        TestFixture fixture = createFixture("analysis-success");

        Long reportId = resumeAnalysisService.triggerAnalysis(fixture.resume().getExternalId(), fixture.user().getId());
        publisherService.publishPendingOutboxMessages();

        ResumeAnalysisOutbox outbox = latestOutboxForReport(reportId);
        resumeAnalysisConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        ResumeAnalysisReport completed = reportRepository.findById(reportId).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, completed.getStatus());
        assertEquals("en-US", completed.getContentLocale());
        assertNotNull(completed.getEvidenceJson());
        assertEquals(85, completed.getOverallScore());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("resume:analysis:processed:" + outbox.getId())));

        ResumeAnalysisOutbox sent = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, sent.getStatus());
        assertNotNull(sent.getSentAt());
    }

    /**
     * Verifies retryable analysis failures follow ACK plus new outbox semantics.
     */
    @Test
    void publishThenConsume_RetryableFailure_AcksAndCreatesNewOutbox() throws java.io.IOException {
        TestFixture fixture = createFixture("analysis-retry");

        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenThrow(new com.josh.interviewj.common.exception.BusinessException("LLM_001", "retry-me"));

        Long reportId = resumeAnalysisService.triggerAnalysis(fixture.resume().getExternalId(), fixture.user().getId());
        publisherService.publishPendingOutboxMessages();

        ResumeAnalysisOutbox firstOutbox = latestOutboxForReport(reportId);
        resumeAnalysisConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        ResumeAnalysisReport pending = reportRepository.findById(reportId).orElseThrow();
        assertEquals(AnalysisStatus.PENDING, pending.getStatus());
        assertEquals(1, pending.getRetryCount());
        assertEquals("retry-me", pending.getErrorMessage());
        assertTrue(!Boolean.TRUE.equals(stringRedisTemplate.hasKey("resume:analysis:processed:" + firstOutbox.getId())));

        ResumeAnalysisOutbox retryOutbox = latestOutboxForReport(reportId);
        assertNotEquals(firstOutbox.getId(), retryOutbox.getId());
        assertEquals(OutboxStatus.NEW, retryOutbox.getStatus());

        mockSuccessfulAnalysis();
        publisherService.publishPendingOutboxMessages();
        resumeAnalysisConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        ResumeAnalysisReport completed = reportRepository.findById(reportId).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getRetryCount());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("resume:analysis:processed:" + retryOutbox.getId())));
    }

    /**
     * Verifies pending recovery can claim and finish an unacknowledged old message.
     */
    @Test
    void redeliveryAfterStateReset_CompletesReport() throws java.io.IOException {
        mockSuccessfulAnalysis();
        TestFixture fixture = createFixture("analysis-recover");

        Long reportId = resumeAnalysisService.triggerAnalysis(fixture.resume().getExternalId(), fixture.user().getId());
        publisherService.publishPendingOutboxMessages();
        ResumeAnalysisOutbox outbox = latestOutboxForReport(reportId);
        jdbcTemplate.update(
                "UPDATE resume_analysis_reports SET status = ?, updated_at = ? WHERE id = ?",
                AnalysisStatus.PENDING.name(),
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                reportId
        );
        resumeAnalysisConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        ResumeAnalysisReport completed = reportRepository.findById(reportId).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, completed.getStatus());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("resume:analysis:processed:" + outbox.getId())));
    }

    /**
     * Creates a parsed resume fixture owned by a real user.
     *
     * @param seed unique seed
     * @return fixture aggregate
     */
    private TestFixture createFixture(String seed) {
        User user = userRepository.save(User.builder()
                .username(seed + "-" + UUID.randomUUID())
                .email(seed + "-" + UUID.randomUUID() + "@example.com")
                .password("hashed")
                .locale("en-US")
                .build());
        grantCredits(user.getId());

        Resume resume = resumeRepository.save(Resume.builder()
                .externalId(UUID.randomUUID())
                .userId(user.getId())
                .fileName(seed + ".pdf")
                .fileUrl("mock://" + seed + ".pdf")
                .fileType("application/pdf")
                .rawText("John Doe\nJava Developer\nSpring Boot\nRedis")
                .parsedContent("""
                        {
                          "personalInfo": {},
                          "education": [],
                          "workExperience": [],
                          "skills": [],
                          "projects": []
                        }
                        """)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .build());

        return new TestFixture(user, resume);
    }

    /**
     * Configures the mocked LLM to return valid stage A and stage B payloads.
     */
    private void mockSuccessfulAnalysis() {
        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0, LlmRequest.class);
            if (request != null
                    && PURPOSE_ANALYSIS.equals(request.purpose())
                    && ResumeAnalysisPrompts.STAGE_A_SYSTEM_PROMPT.equals(request.systemPrompt())) {
                return new LlmResponse(EVIDENCE_JSON, "mock", "mock");
            }
            return new LlmResponse(SCORING_JSON, "mock", "mock");
        });
    }

    /**
     * Reads the latest published analysis stream record.
     *
     * @return last stream record
     */
    private ResumeAnalysisMessage readLastMessage() {
        ResumeAnalysisOutbox outbox = outboxRepository.findAll().stream()
                .max(java.util.Comparator.comparing(ResumeAnalysisOutbox::getId))
                .orElseThrow();
        return new ResumeAnalysisMessage(outbox.getReportId(), outbox.getResumeId(), outbox.getResumeExternalId(), outbox.getId());
    }

    /**
     * Returns the newest analysis outbox row for a report.
     *
     * @param reportId report id
     * @return latest outbox row
     */
    private ResumeAnalysisOutbox latestOutboxForReport(Long reportId) {
        return outboxRepository.findAll().stream()
                .filter(item -> item.getReportId().equals(reportId))
                .max(java.util.Comparator.comparing(ResumeAnalysisOutbox::getId))
                .orElseThrow();
    }

    private void resetResumeAnalysisRedisState() {
        Set<String> processedKeys = stringRedisTemplate.keys("resume:analysis:processed:*");
        if (processedKeys != null && !processedKeys.isEmpty()) {
            stringRedisTemplate.delete(processedKeys);
        }
    }

    /**
     * Small aggregate used by the test fixture setup.
     */
    private record TestFixture(User user, Resume resume) {
    }

    private void grantCredits(Long userId) {
        creditWalletRepository.save(CreditWallet.builder()
                .userId(userId)
                .purchasedBalanceMicros(1_000_000L)
                .build());
    }

    private void stubPublishSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
