package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
import com.josh.interviewj.ragqa.service.QueryEmbeddingService;
import com.josh.interviewj.usage.repository.UsageRejectionRecordRepository;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@SpringBootTest
class KnowledgeBaseQueryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KbDocumentRepository kbDocumentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private KnowledgeBaseQueryService knowledgeBaseQueryService;

    @Autowired
    private QueryUnderstandingProperties queryUnderstandingProperties;

    @Autowired
    private CreditWalletRepository creditWalletRepository;

    @Autowired
    private UsageRejectionRecordRepository usageRejectionRecordRepository;

    @MockitoBean
    private QueryEmbeddingService queryEmbeddingService;

    @MockitoBean
    private LLMService llmService;

    private User owner;
    private User outsider;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        documentChunkRepository.deleteAllInBatch();
        kbDocumentRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        usageRejectionRecordRepository.deleteAllInBatch();
        creditWalletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        owner = userRepository.save(User.builder()
                .username("query-owner")
                .email("query-owner@example.com")
                .password("hashed")
                .build());
        grantCredits(owner.getId());

        outsider = userRepository.save(User.builder()
                .username("query-outsider")
                .email("query-outsider@example.com")
                .password("hashed")
                .build());
        grantCredits(outsider.getId());

        knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Query KB")
                .description("RAG KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        QueryUnderstandingProperties.AliasEntry aliasEntry = new QueryUnderstandingProperties.AliasEntry();
        aliasEntry.setAlias("aof");
        aliasEntry.setCanonical("append only file");
        queryUnderstandingProperties.setEnabled(true);
        queryUnderstandingProperties.setRewriteEnabled(false);
        queryUnderstandingProperties.setDualBranchEnabled(false);
        queryUnderstandingProperties.setLogQueryMaxChars(300);
        queryUnderstandingProperties.setAliasDictionary(java.util.List.of(aliasEntry));
    }

    @Test
    void askQuestion_KnowledgeBaseOwnedByAnotherUser_ThrowsAuth006() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis?");

        BusinessException exception = assertThrows(BusinessException.class, () ->
                knowledgeBaseQueryService.askQuestion(outsider.getUsername(), knowledgeBase.getExternalId(), request));

        assertEquals("AUTH_006", exception.getErrorCode());
    }

    @Test
    void askQuestion_NoCompletedChunks_ThrowsKb004() {
        kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("processing.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://processing.pdf")
                .status(KbDocumentStatus.PROCESSING)
                .expectedChunkCount(1)
                .embeddedChunkCount(0)
                .chunkCount(0)
                .build());

        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("Redis?")))
                .thenReturn(embeddingExecution(1.0));

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis?");
        request.setTopK(5);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                knowledgeBaseQueryService.askQuestion(owner.getUsername(), knowledgeBase.getExternalId(), request));

        assertEquals("KB_004", exception.getErrorCode());
    }

    @Test
    void askQuestion_Success_MapsSourcesAndUsesDefaultTopKForZero() {
        KbDocument completedDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("completed.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://completed.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .expectedChunkCount(1)
                .embeddedChunkCount(1)
                .chunkCount(1)
                .build());

        String longContent = "A".repeat(540);
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                completedDocument.getId(),
                knowledgeBase.getId(),
                longContent,
                0,
                0,
                longContent.length(),
                longContent.length(),
                "{}"
        );
        documentChunkRepository.updateEmbeddingIfNull(completedDocument.getId(), 0, vectorLiteral(1.0));

        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("Redis?")))
                .thenReturn(embeddingExecution(1.0));
        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenReturn(new LlmResponse("{\"answer\":\"Redis answer\"}", "default", "qwen"));

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis?");
        request.setTopK(0);
        request.setIncludeSources(true);

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion(
                owner.getUsername(),
                knowledgeBase.getExternalId(),
                request
        );

        assertEquals("Redis answer", response.getAnswer());
        assertEquals(1, response.getRetrievedChunkCount());
        assertNotNull(response.getChatSessionId());
        assertNotNull(response.getUserMessageId());
        assertNotNull(response.getAssistantMessageId());
        assertNotNull(response.getSources());
        assertEquals(1, response.getSources().size());
        assertEquals(completedDocument.getExternalId(), response.getSources().getFirst().getDocumentId());
        assertEquals(0, response.getSources().getFirst().getChunkIndex());
        assertEquals(503, response.getSources().getFirst().getContent().length());
    }

    @Test
    void askQuestion_WhenQueryUnderstandingDisabled_RevertsToRawQueryBaseline() {
        queryUnderstandingProperties.setEnabled(false);

        KbDocument completedDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("completed.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://completed.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .expectedChunkCount(1)
                .embeddedChunkCount(1)
                .chunkCount(1)
                .build());

        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                completedDocument.getId(),
                knowledgeBase.getId(),
                "AOF content",
                0,
                0,
                10,
                10,
                "{}"
        );
        documentChunkRepository.updateEmbeddingIfNull(completedDocument.getId(), 0, vectorLiteral(1.0));

        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("AOF 持久化")))
                .thenReturn(embeddingExecution(1.0));
        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenReturn(new LlmResponse("{\"answer\":\"Redis answer\"}", "default", "qwen"));

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AOF 持久化");

        knowledgeBaseQueryService.askQuestion(owner.getUsername(), knowledgeBase.getExternalId(), request);

        verify(queryEmbeddingService).embedQueryWithUsage(any(), anyString(), eq("AOF 持久化"));
    }

    @Test
    void askQuestion_Phase1UsesNormalizedQueryForEmbeddingButRawQuestionForPrompt() {
        KbDocument completedDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("completed.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://completed.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .expectedChunkCount(1)
                .embeddedChunkCount(1)
                .chunkCount(1)
                .build());

        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                completedDocument.getId(),
                knowledgeBase.getId(),
                "AOF content",
                0,
                0,
                10,
                10,
                "{}"
        );
        documentChunkRepository.updateEmbeddingIfNull(completedDocument.getId(), 0, vectorLiteral(1.0));

        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("AOF 持久化 append only file")))
                .thenReturn(embeddingExecution(1.0));
        when(llmService.generateStructuredJson(any(LlmRequest.class), org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any()))
                .thenReturn(new LlmResponse("{\"answer\":\"Redis answer\"}", "default", "qwen"));

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AOF 持久化");

        knowledgeBaseQueryService.askQuestion(owner.getUsername(), knowledgeBase.getExternalId(), request);

        verify(queryEmbeddingService).embedQueryWithUsage(any(), anyString(), eq("AOF 持久化 append only file"));
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmService, org.mockito.Mockito.atLeastOnce()).generateStructuredJson(requestCaptor.capture(), org.mockito.ArgumentMatchers.isNull());
        LlmRequest ragRequest = requestCaptor.getAllValues().stream()
                .filter(item -> "rag".equals(item.purpose()))
                .findFirst()
                .orElseThrow();
        assertTrue(ragRequest.userPrompt().contains("Question:\nAOF 持久化\n\nContext:\n"));
        assertFalse(ragRequest.userPrompt().contains("Question:\nAOF 持久化 append only file"));
    }

    private float[] vector(double first) {
        float[] vector = new float[2048];
        vector[0] = (float) first;
        return vector;
    }

    private QueryEmbeddingService.EmbeddingExecutionResult embeddingExecution(double first) {
        return new QueryEmbeddingService.EmbeddingExecutionResult(
                new EmbeddingResponse(vector(first), "mock-provider", "mock-model"),
                null,
                null
        );
    }

    private String vectorLiteral(double first) {
        StringBuilder builder = new StringBuilder("[");
        builder.append(first);
        for (int index = 1; index < 2048; index++) {
            builder.append(",0.0");
        }
        builder.append(']');
        return builder.toString();
    }

    private void grantCredits(Long userId) {
        creditWalletRepository.save(CreditWallet.builder()
                .userId(userId)
                .purchasedBalanceMicros(1_000_000L)
                .build());
    }
}
