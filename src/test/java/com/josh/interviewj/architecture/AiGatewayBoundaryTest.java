package com.josh.interviewj.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AiGatewayBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java/com/josh/interviewj");
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import com.josh.interviewj.usage.service.UsageRecordCandidate;",
            "import com.josh.interviewj.usage.service.UsageRecordingFacade;",
            "import com.josh.interviewj.usage.service.UsageFailureCompensationService;",
            "import com.josh.interviewj.usage.service.UsageSettlementService;",
            "import com.josh.interviewj.usage.service.UsageRejectionRecordingService;",
            "import com.josh.interviewj.llm.core.LlmClient;",
            "import com.josh.interviewj.llm.LLMService;",
            "import com.josh.interviewj.llm.core.EmbeddingClient;",
            "import com.josh.interviewj.llm.EmbeddingService;",
            "import com.josh.interviewj.ragqa.service.RerankClient;"
    );

    @Test
    void businessModules_DoNotBypassAiGateway() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::isBusinessModule)
                    .flatMap(this::findViolations)
                    .toList();

            assertThat(violations).isEmpty();
        }
    }

    private boolean isBusinessModule(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return !normalized.contains("/usage/")
                && !normalized.contains("/llm/gateway/")
                && !normalized.endsWith("/llm/LLMService.java")
                && !normalized.endsWith("/llm/EmbeddingService.java")
                && !normalized.endsWith("/ragqa/service/RerankClient.java")
                && !normalized.endsWith("/knowledgebase/service/KbEmbeddingService.java");
    }

    private Stream<String> findViolations(Path path) {
        try {
            String content = Files.readString(path);
            return FORBIDDEN_IMPORTS.stream()
                    .filter(content::contains)
                    .map(forbiddenImport -> path.getFileName() + " -> " + forbiddenImport);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to read " + path, exception);
        }
    }
}
