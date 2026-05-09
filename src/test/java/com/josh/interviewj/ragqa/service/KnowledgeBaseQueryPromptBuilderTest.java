package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.core.LlmClient;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.FusedChunk;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import com.josh.interviewj.ragqa.repository.ChunkSparseSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeBaseQueryPromptBuilderTest {

    private KnowledgeBaseQueryService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                properties,
                new QueryNormalizationService(properties),
                new QueryProfileDetector(),
                new QueryRewriteService(mock(AiOperationGateway.class), objectMapper, properties)
        );
        service = new KnowledgeBaseQueryService(
                mock(KnowledgeBaseAccessService.class),
                queryUnderstandingService,
                new RetrievalPlanBuilder(properties),
                mock(QueryEmbeddingService.class),
                mock(ChunkSearchRepository.class),
                mock(ChunkSparseSearchRepository.class),
                mock(KbDocumentRepository.class),
                objectMapper,
                new RetrievalResultFusionService(),
                Executors.newSingleThreadExecutor(),
                mock(RagQaChatSessionService.class),
                new ChatProperties(),
                mock(AiOperationGateway.class)
        );
    }

    @Test
    void buildPromptFromContextBlocks_IncludesSectionHeader() throws Exception {
        String prompt = (String) invoke(
                "buildPromptFromContextBlocks",
                new Class[]{String.class, List.class, ChatContextWindow.class},
                "什么是 JWT？",
                List.of(new ContextBlock(
                        1L,
                        UUID.randomUUID(),
                        "Auth Guide",
                        List.of("认证", "JWT"),
                        List.of(1),
                        List.of(1, 2),
                        "JWT 是一种 token 格式",
                        30,
                        0.9D,
                        Map.of(),
                        Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                        ContextBlock.AssemblyStrategy.SECTION_PRIORITY
                )),
                new ChatContextWindow(List.of(), false, 0, 0)
        );

        assertThat(prompt).contains("--- [Source: Auth Guide, Section: 认证 > JWT] ---");
        assertThat(prompt).contains("JWT 是一种 token 格式");
    }

    @Test
    void buildPromptFromRankedSeeds_UsesChunkStyleMarker() throws Exception {
        String prompt = (String) invoke(
                "buildPromptFromRankedSeeds",
                new Class[]{String.class, List.class, ChatContextWindow.class},
                "JWT 登录失败怎么办？",
                List.of(new RankedChunkCandidate(
                        1L,
                        UUID.randomUUID(),
                        "Auth Guide",
                        2,
                        "JWT 过期会导致登录失败",
                        null,
                        Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                        0.88D,
                        0.88D,
                        0.80D
                )),
                new ChatContextWindow(List.of(), false, 0, 0)
        );

        assertThat(prompt).contains("[Document: Auth Guide, Chunk: 2]");
        assertThat(prompt).contains("JWT 过期会导致登录失败");
    }

    @Test
    void extractAnswer_FallsBackToProvidedTexts() throws Exception {
        String answer = (String) invoke(
                "extractAnswer",
                new Class[]{String.class, List.class},
                "",
                List.of("fallback-1", "fallback-2")
        );

        assertThat(answer).isEqualTo("fallback-1");
    }

    private Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = KnowledgeBaseQueryService.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
}
