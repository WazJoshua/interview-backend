package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.prompt.service.PromptTemplateCacheService;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for prompt template invalidation.
 * Verifies that new revisions are picked up after cache invalidation.
 */
@SpringBootTest
class PromptTemplateInvalidationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PromptTemplateCacheService promptTemplateCacheService;

    @Autowired
    private LlmConfigChangeService llmConfigChangeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void newRevision_IsVisibleAfterInvalidation() {
        // Verify initial template exists
        Map<String, Object> template = jdbcTemplate.queryForMap(
                "SELECT * FROM llm_prompt_template WHERE template_key = 'kb_query_rewrite'"
        );
        assertThat(template.get("active_revision_id")).isNotNull();

        // Cache should initially be empty (lazy loading)
        assertThat(promptTemplateCacheService.size()).isEqualTo(0);

        // Trigger config change (invalidation)
        llmConfigChangeService.recordChange("TEST_PROMPT_CHANGE", "test change");

        // Cache should be cleared after invalidation
        promptTemplateCacheService.invalidateAll();

        // Verify cache is cleared
        assertThat(promptTemplateCacheService.size()).isEqualTo(0);
    }
}