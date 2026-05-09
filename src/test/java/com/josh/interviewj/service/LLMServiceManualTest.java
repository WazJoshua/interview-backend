package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmClient;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.provider.LLMHttpClient;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.josh.interviewj.llm.routing.LlmRouter;
import com.josh.interviewj.llm.support.LlmPurposeCircuitBreakerRegistry;
import com.josh.interviewj.llm.template.ClasspathTemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMServiceManualTest {

    private static final String DEFAULT_BASE_URL = "https://www.example.com/v1";
    private static final String DISPATCHER_EXAMPLE_BASE_URL = "https://www.example.com/v1";
    private static final String DEFAULT_PARSE_MODEL = "qwen3.5-35b-a3b";
    private static final String DEFAULT_ANALYSIS_MODEL = "qwen3.5-27b";
    private static final String DEFAULT_DISPATCHER_RAG_MODEL = "gpt-5.1-codex-mini";
    private static final String DEFAULT_MANUAL_TIMEOUT_MS = "30000";
    private static final String DEFAULT_MANUAL_ANALYSIS_TIMEOUT_MS = "300000";
    private static final String DEFAULT_MANUAL_MAX_RETRIES = "1";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * Verifies a real provider call still returns a parseable JSON object.
     *
     * @throws Exception when JSON parsing fails
     */
    @Test
    void generateParseStructuredJson_RealProviderCall_ReturnsJsonObject() throws Exception {
        LLMService llmService = createManualService();
        String rawText = """
                Josh Zhang
                Java Backend Engineer
                Skills: Java, Spring Boot, PostgreSQL, Redis
                Experience: Built REST APIs and async resume processing services.
                """;

        String json = llmService.generateParseStructuredJson(
                """
                        You are a structured parser.
                        Convert the input text into a JSON object.
                        Required keys: name, title, skills, summary.
                        If a field is missing, use an empty string or an empty array.
                        Return JSON only.
                        """,
                "Parse this text into the required JSON object:\n" + rawText
        );

        JsonNode root = objectMapper.readTree(json);
        System.out.println("LLM parse response: " + json);

        assertNotNull(json);
        assertTrue(root.isObject(), "LLM response should be a JSON object.");
        assertTrue(root.size() > 0, "LLM response should not be an empty JSON object.");
    }

    /**
     * Verifies the dispatcher_rc provider can handle a minimal hello request through the rag route.
     *
     * @throws Exception when JSON parsing fails
     */
    @Test
    void generateRagStructuredJson_DispatcherRcRealProviderCall_ReturnsHelloJsonObject() throws Exception {
        LLMService llmService = createDispatcherRcManualService();

        String json = llmService.generateStructuredJson(new LlmRequest(
                "rag",
                """
                        You are a JSON response assistant.
                        Return exactly one JSON object with a single key named "message".
                        The value must be the exact string "Hello".
                        Return JSON only.
                        """,
                "Say hello."
        )).content();

        JsonNode root = objectMapper.readTree(json);
        System.out.println("dispatcher_rc rag response: " + json);

        assertNotNull(json);
        assertTrue(root.isObject(), "dispatcher_rc response should be a JSON object.");
        assertEquals("Hello", root.path("message").asText(), "dispatcher_rc should return the requested hello payload.");
    }

    /**
     * Verifies the real LLM interface can complete the resume analysis flow through {@link ResumeAnalysisService}.
     */
    @Test
    void performAnalysis_RealProviderCall_CompletesResumeAnalysis() {
        assumeTrue(isManualResumeAnalysisRunEnabled(),
               "Set RUN_MANUAL_RESUME_ANALYSIS_TEST=true or -DrunManualResumeAnalysisTest=true to execute this test.");

        ResumeRepository resumeRepository = mock(ResumeRepository.class);
        ResumeAnalysisReportRepository reportRepository = mock(ResumeAnalysisReportRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        ExecutorService virtualThreadExecutor = mock(ExecutorService.class);

        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(reportRepository.updateStatus(anyLong(), any(AnalysisStatus.class))).thenReturn(1);
        when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportRepository.save(any(ResumeAnalysisReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Long reportId = 9001L;
        Long resumeId = 7001L;
        Long userId = 1L;
        UUID resumeExternalId = UUID.randomUUID();

        Resume resume = Resume.builder()
                .id(resumeId)
                .externalId(resumeExternalId)
                .userId(userId)
                .fileName("manual-resume.txt")
                .fileUrl("manual://resume")
                .fileType("text/plain")
                .fileSize(512L)
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .rawText("""
                        Josh Zhang
                        Senior Java Backend Engineer
                        Skills: Java, Spring Boot, PostgreSQL, Redis, Kafka, Docker
                        Experience:
                        - Built async resume parsing and analysis services
                        - Designed REST APIs and background job orchestration
                        - Improved observability and retry handling for LLM workflows
                        Projects:
                        - Intelligent interview platform
                        Education:
                        - BEng in Software Engineering
                        """)
                .parsedContent("""
                        {
                          "personalInfo": {
                            "name": "Josh Zhang",
                            "title": "Senior Java Backend Engineer"
                          },
                          "education": [
                            {
                              "school": "Example University",
                              "degree": "BEng in Software Engineering"
                            }
                          ],
                          "workExperience": [
                            {
                              "company": "Example Tech",
                              "title": "Senior Java Backend Engineer",
                              "highlights": [
                                "Built async resume parsing and analysis services",
                                "Designed REST APIs and background job orchestration"
                              ]
                            }
                          ],
                          "skills": [
                            "Java",
                            "Spring Boot",
                            "PostgreSQL",
                            "Redis",
                            "Kafka",
                            "Docker"
                          ],
                          "projects": [
                            {
                              "name": "Intelligent Interview Platform",
                              "description": "AI-assisted interview preparation platform."
                            }
                          ]
                        }
                        """)
                .build();

        ResumeAnalysisReport report = ResumeAnalysisReport.builder()
                .id(reportId)
                .resumeId(resumeId)
                .userId(userId)
                .status(AnalysisStatus.PENDING)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .build();

        when(reportRepository.findById(reportId)).thenReturn(java.util.Optional.of(report));
        when(resumeRepository.findById(resumeId)).thenReturn(java.util.Optional.of(resume));

        ResumeAnalysisService resumeAnalysisService = createManualResumeAnalysisService(
                resumeRepository,
                reportRepository,
                transactionManager
        );

        resumeAnalysisService.performAnalysis(reportId);

        String diagnostics = buildResumeAnalysisDiagnostics(report, resume);
        System.out.println(diagnostics);

        assertEquals(AnalysisStatus.COMPLETED, report.getStatus(),
                "Resume analysis should complete successfully.\n" + diagnostics);
        assertEquals(AnalysisStatus.COMPLETED, resume.getAnalysisStatus(), "Resume state should be updated to COMPLETED.");
        assertNotNull(report.getModelName(), "Model name should be captured.");
        assertNotNull(report.getEvidenceJson(), "Evidence JSON should be persisted.");
        assertTrue(!report.getEvidenceJson().isBlank(), "Evidence JSON should not be blank.");
        assertNotNull(report.getSummary(), "Summary should be populated.");
        verify(reportRepository).save(report);
    }

    /**
     * Creates a manual LLM service wired to the SDK-backed provider adapter.
     *
     * @return manual service instance
     */
    private LLMService createManualService() {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_LLM_TEST=true or -DrunManualLlmTest=true to execute this test.");

        String apiKey = System.getenv("ALI_API");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "Set ALI_API before running this test.");
        String baseUrl = resolveRequiredBaseUrl();

        LlmProperties properties = new LlmProperties();

        LlmProperties.PurposeRoutingProperties parseRoute = new LlmProperties.PurposeRoutingProperties();
        parseRoute.setStrategy("single");
        parseRoute.setProvider("default");

        LlmProperties.PurposeRoutingProperties analysisRoute = new LlmProperties.PurposeRoutingProperties();
        analysisRoute.setStrategy("single");
        analysisRoute.setProvider("default");
        analysisRoute.setTimeoutMs(Integer.parseInt(System.getProperty(
                "app.llm.routing.purposes.analysis.timeout-ms",
                DEFAULT_MANUAL_ANALYSIS_TIMEOUT_MS
        )));

        properties.getRouting().setPurposes(Map.of(
                "parse", parseRoute,
                "analysis", analysisRoute
        ));

        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl(baseUrl);
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(Integer.parseInt(System.getProperty(
                "app.llm.providers.default.timeout-ms",
                DEFAULT_MANUAL_TIMEOUT_MS
        )));
        providerProperties.setMaxRetries(Integer.parseInt(System.getProperty(
                "app.llm.providers.default.max-retries",
                DEFAULT_MANUAL_MAX_RETRIES
        )));
        providerProperties.setRetryBackoffMs(Integer.parseInt(System.getProperty(
                "app.llm.providers.default.retry-backoff-ms",
                "500"
        )));

        LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.put("parse", System.getProperty(
                "app.llm.providers.default.chat.models.parse",
                DEFAULT_PARSE_MODEL
        ));
        models.put("analysis", System.getProperty(
                "app.llm.providers.default.chat.models.analysis",
                DEFAULT_ANALYSIS_MODEL
        ));
        chatProperties.setModels(models);
        providerProperties.setChat(chatProperties);

        properties.setProviders(Map.of("default", providerProperties));

        LLMHttpClient sdkClient = new LLMHttpClient(new OpenAiClientFactory());
        tools.jackson.databind.ObjectMapper templateObjectMapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                new ClasspathTemplateRegistry(new DefaultResourceLoader()),
                new TemplateRequestExecutor(templateObjectMapper),
                new TemplateResponseExtractor(templateObjectMapper)
        );
        LlmRouter llmRouter = new LlmRouter((com.josh.interviewj.llm.routing.DatabaseLlmRouteResolver) null);
        LlmPurposeCircuitBreakerRegistry breakerRegistry = new LlmPurposeCircuitBreakerRegistry(5, 30, 60);
        return new LLMService(objectMapper, llmRouter, executor, breakerRegistry);
    }

    /**
     * Creates a manual LLM service wired to the dispatcher_rc provider using the rag route.
     *
     * @return manual service instance
     */
    private LLMService createDispatcherRcManualService() {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_LLM_TEST=true or -DrunManualLlmTest=true to execute this test.");

        String apiKey = System.getenv("RAG_API");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "Set RAG_API before running this test.");

        String baseUrl = System.getProperty(
                "app.llm.providers.dispatcher_rc.base-url",
                System.getenv().getOrDefault("RAG_LLM_BASE_URL", DISPATCHER_EXAMPLE_BASE_URL)
        );
        assumeTrue(baseUrl != null && !baseUrl.isBlank(),
                "Set RAG_LLM_BASE_URL or -Dapp.llm.providers.dispatcher_rc.base-url before running this test.");

        LlmProperties properties = new LlmProperties();

        LlmProperties.PurposeRoutingProperties ragRoute = new LlmProperties.PurposeRoutingProperties();
        ragRoute.setStrategy("single");
        ragRoute.setProvider("dispatcher_rc");
        properties.getRouting().setPurposes(Map.of("rag", ragRoute));

        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl(baseUrl);
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(Integer.parseInt(System.getProperty(
                "app.llm.providers.dispatcher_rc.timeout-ms",
                DEFAULT_MANUAL_TIMEOUT_MS
        )));
        providerProperties.setMaxRetries(Integer.parseInt(System.getProperty(
                "app.llm.providers.dispatcher_rc.max-retries",
                DEFAULT_MANUAL_MAX_RETRIES
        )));
        providerProperties.setRetryBackoffMs(Integer.parseInt(System.getProperty(
                "app.llm.providers.dispatcher_rc.retry-backoff-ms",
                "500"
        )));

        LlmProperties.TemplateProperties templateProperties = new LlmProperties.TemplateProperties();
        templateProperties.setEnabled(Boolean.parseBoolean(System.getProperty(
                "app.llm.providers.dispatcher_rc.template.enabled",
                "false"
        )));
        templateProperties.setStrict(Boolean.parseBoolean(System.getProperty(
                "app.llm.providers.dispatcher_rc.template.strict",
                "false"
        )));
        providerProperties.setTemplate(templateProperties);

        LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.put("rag", System.getProperty(
                "app.llm.providers.dispatcher_rc.chat.models.rag",
                DEFAULT_DISPATCHER_RAG_MODEL
        ));
        chatProperties.setModels(models);
        providerProperties.setChat(chatProperties);

        properties.setProviders(Map.of("dispatcher_rc", providerProperties));

        LLMHttpClient sdkClient = new LLMHttpClient(new OpenAiClientFactory());
        tools.jackson.databind.ObjectMapper templateObjectMapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                new ClasspathTemplateRegistry(new DefaultResourceLoader()),
                new TemplateRequestExecutor(templateObjectMapper),
                new TemplateResponseExtractor(templateObjectMapper)
        );
        LlmRouter llmRouter = new LlmRouter((com.josh.interviewj.llm.routing.DatabaseLlmRouteResolver) null);
        LlmPurposeCircuitBreakerRegistry breakerRegistry = new LlmPurposeCircuitBreakerRegistry(5, 30, 60);
        return new LLMService(objectMapper, llmRouter, executor, breakerRegistry);
    }

    /**
     * Builds a concise diagnostic summary for manual resume analysis verification.
     *
     * @param report analysis report after execution
     * @param resume resume state after execution
     * @return diagnostic text
     */
    private String buildResumeAnalysisDiagnostics(ResumeAnalysisReport report, Resume resume) {
        String evidencePreview = report.getEvidenceJson();
        if (evidencePreview != null && evidencePreview.length() > 300) {
            evidencePreview = evidencePreview.substring(0, 300) + "...";
        }

        return """
                Resume analysis diagnostics:
                - reportStatus: %s
                - resumeAnalysisStatus: %s
                - modelName: %s
                - errorMessage: %s
                - summary: %s
                - completenessScore: %s
                - clarityScore: %s
                - overallScore: %s
                - timeoutMs: %s
                - analysisTimeoutMs: %s
                - maxRetries: %s
                - evidencePreview: %s
                """.formatted(
                report.getStatus(),
                resume.getAnalysisStatus(),
                report.getModelName(),
                report.getErrorMessage(),
                report.getSummary(),
                report.getCompletenessScore(),
                report.getClarityScore(),
                report.getOverallScore(),
                System.getProperty("app.llm.providers.default.timeout-ms", DEFAULT_MANUAL_TIMEOUT_MS),
                System.getProperty("app.llm.routing.purposes.analysis.timeout-ms", DEFAULT_MANUAL_ANALYSIS_TIMEOUT_MS),
                System.getProperty("app.llm.providers.default.max-retries", DEFAULT_MANUAL_MAX_RETRIES),
                evidencePreview
        );
    }

    /**
     * Creates a manual resume analysis service backed by the real LLM provider and mocked persistence dependencies.
     *
     * @param resumeRepository   mocked resume repository
     * @param reportRepository   mocked report repository
     * @param transactionManager mocked transaction manager
     * @return manual resume analysis service
     */
    private ResumeAnalysisService createManualResumeAnalysisService(
            ResumeRepository resumeRepository,
            ResumeAnalysisReportRepository reportRepository,
            PlatformTransactionManager transactionManager
    ) {
        LlmClient llmClient = createManualService();
        com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository outboxRepository =
                mock(com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        return new ResumeAnalysisService(
                resumeRepository,
                reportRepository,
                outboxRepository,
                userRepository,
                mock(AiOperationGateway.class),
                objectMapper
        );
    }

    /**
     * Resolves the compatible-mode base URL for manual verification.
     *
     * @return base URL
     */
    private String resolveRequiredBaseUrl() {
        String baseUrl = System.getProperty(
                "app.llm.providers.default.base-url",
                System.getenv().getOrDefault("LLM_BASE_URL", DEFAULT_BASE_URL)
        );
        assumeTrue(baseUrl != null && !baseUrl.isBlank(),
                "Set LLM_BASE_URL or -Dapp.llm.providers.default.base-url before running this test.");
        return baseUrl;
    }

    /**
     * Returns whether the optional manual test flag is enabled.
     *
     * @return {@code true} when manual execution is enabled
     */
    private boolean isManualRunEnabled() {
        String systemProperty = System.getProperty("runManualLlmTest", "false");
        String environmentVariable = System.getenv().getOrDefault("RUN_MANUAL_LLM_TEST", "false");
        return Boolean.parseBoolean(systemProperty) || Boolean.parseBoolean(environmentVariable);
    }

    /**
     * Returns whether the manual resume analysis verification is enabled.
     *
     * @return {@code true} when manual resume analysis execution is enabled
     */
    private boolean isManualResumeAnalysisRunEnabled() {
        String systemProperty = System.getProperty("runManualResumeAnalysisTest", "false");
        String environmentVariable = System.getenv().getOrDefault("RUN_MANUAL_RESUME_ANALYSIS_TEST", "false");
        return Boolean.parseBoolean(systemProperty) || Boolean.parseBoolean(environmentVariable);
    }
}
