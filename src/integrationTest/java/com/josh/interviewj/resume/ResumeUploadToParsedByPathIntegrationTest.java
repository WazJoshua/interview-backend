package com.josh.interviewj.resume;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.resume.consumer.ResumeParseConsumer;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.outbox.OutboxPublisherService;
import com.josh.interviewj.resume.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class ResumeUploadToParsedByPathIntegrationTest extends IntegrationTestBase {

    private static final String PARSED_CONTENT_JSON = """
            {
              "personalInfo": {},
              "education": [],
              "workExperience": [],
              "skills": [],
              "projects": []
            }
            """;

    private static final Path RESUME_FILE_PATH = Path.of(
            "src/test/resources/knowledgebase/evaluation/structure-aware/fixtures/tutorial.md"
    );
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private ResumeParseConsumer resumeParseConsumer;

    @Autowired
    private ResumeParseOutboxRepository resumeParseOutboxRepository;

    @Autowired
    private CreditWalletRepository creditWalletRepository;

    @MockitoBean
    private LLMService llmService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUpMocks() {
        stubPublishSuccess();
        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenReturn(new LlmResponse(PARSED_CONTENT_JSON, "mock", "mock"));
    }

    @Test
    void uploadThenParse_WithSpecifiedFilePath_CompletesToParsed() throws IOException {
        Path resumeFile = resolveResumePath();
        MockMultipartFile file = toMultipartFile(resumeFile);

        String username = "it-path-user-" + UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("password_hash")
                .build());
        creditWalletRepository.save(CreditWallet.builder()
                .userId(user.getId())
                .purchasedBalanceMicros(1_000_000L)
                .build());

        ResumeUploadResponseDTO upload = resumeService.uploadResume(username, file, "Backend Engineer");
        Resume uploaded = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(upload.getId(), user.getId())
                .orElseThrow();
        assertEquals(ResumeStatus.UPLOADED, uploaded.getStatus());

        ResumeParseResponseDTO parseTriggered = resumeService.triggerParse(username, upload.getId());
        assertEquals(ResumeStatus.PENDING, parseTriggered.getStatus());

        outboxPublisherService.publishPendingOutboxMessages();
        resumeParseConsumer.onMessage(readLastParseMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        Resume parsed = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(upload.getId(), user.getId())
                .orElseThrow();
        assertEquals(ResumeStatus.PARSED, parsed.getStatus());
        assertNotNull(parsed.getParsedAt());
        assertNotNull(parsed.getRawText());
        assertFalse(parsed.getRawText().isBlank());
        assertNotNull(parsed.getParsedContent());
        assertTrue(parsed.getParsedContent().contains("\"personalInfo\""));
    }

    private static Path resolveResumePath() {
        Path path = RESUME_FILE_PATH.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "Resume file not found: " + path);
        return path;
    }

    private static MockMultipartFile toMultipartFile(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String contentType = resolveContentType(filePath);
        byte[] bytes = Files.readAllBytes(filePath);
        return new MockMultipartFile("file", fileName, contentType, bytes);
    }

    private static String resolveContentType(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex > -1 ? fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT) : "";
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "rtf" -> "application/rtf";
            case "md" -> "text/markdown";
            default -> {
                String detected = Files.probeContentType(filePath);
                yield detected == null ? "application/octet-stream" : detected;
            }
        };
    }

    private ResumeParseMessage readLastParseMessage() {
        ResumeParseOutbox outbox = resumeParseOutboxRepository.findAll().stream()
                .max(java.util.Comparator.comparing(ResumeParseOutbox::getId))
                .orElseThrow();
        return new ResumeParseMessage(outbox.getResumeId(), outbox.getResumeExternalId(), outbox.getId());
    }

    private void stubPublishSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
