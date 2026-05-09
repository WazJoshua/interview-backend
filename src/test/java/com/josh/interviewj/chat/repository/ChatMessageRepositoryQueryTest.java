package com.josh.interviewj.chat.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageRepositoryQueryTest {

    @Test
    void averageAssistantConfidenceQuery_usesJsonbExistsInsteadOfQuestionMarkOperator() throws Exception {
        Method method = ChatMessageRepository.class.getMethod(
                "averageAssistantConfidenceByKnowledgeBase",
                java.util.UUID.class
        );

        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("jsonb_exists(message.metadata, 'confidence')");
        assertThat(query.value()).doesNotContain("message.metadata ? 'confidence'");
    }
}
