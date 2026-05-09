package com.josh.interviewj.interview.repository;

import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.interview.model.InterviewMode;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InterviewSessionRepositoryQueryTest.TestJpaApplication.class, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class InterviewSessionRepositoryQueryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("interview_query_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private InterviewSessionRepository interviewSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findTimedOutInProgressSessionIds_FiltersByStatusDeletionCutoffAndFallbackOrder() throws Exception {
        Method method = InterviewSessionRepository.class.getMethod(
                "findTimedOutInProgressSessionIds",
                LocalDateTime.class,
                org.springframework.data.domain.Pageable.class
        );
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.value()).contains("chat.externalId = session.chatSessionId");
        assertThat(query.value()).contains("coalesce(chat.lastMessageAt, session.startTime, session.updatedAt)");

        ChatSession oldestChat = persistChat(LocalDateTime.parse("2026-03-28T08:00:00"));
        ChatSession fallbackChat = persistChat(null);
        ChatSession recentChat = persistChat(LocalDateTime.parse("2026-03-28T09:50:00"));
        ChatSession completedChat = persistChat(LocalDateTime.parse("2026-03-28T07:30:00"));
        ChatSession deletedChat = persistChat(LocalDateTime.parse("2026-03-28T07:00:00"));

        InterviewSession oldest = persistSession(oldestChat.getExternalId(), InterviewStatus.IN_PROGRESS,
                LocalDateTime.parse("2026-03-28T08:10:00"), null, LocalDateTime.parse("2026-03-28T08:10:00"));
        InterviewSession fallback = persistSession(fallbackChat.getExternalId(), InterviewStatus.IN_PROGRESS,
                LocalDateTime.parse("2026-03-28T08:30:00"), null, LocalDateTime.parse("2026-03-28T08:30:00"));
        persistSession(recentChat.getExternalId(), InterviewStatus.IN_PROGRESS,
                LocalDateTime.parse("2026-03-28T09:40:00"), null, LocalDateTime.parse("2026-03-28T09:45:00"));
        persistSession(completedChat.getExternalId(), InterviewStatus.COMPLETED,
                LocalDateTime.parse("2026-03-28T07:40:00"), null, LocalDateTime.parse("2026-03-28T07:45:00"));
        persistSession(deletedChat.getExternalId(), InterviewStatus.IN_PROGRESS,
                LocalDateTime.parse("2026-03-28T07:10:00"), LocalDateTime.parse("2026-03-28T09:00:00"),
                LocalDateTime.parse("2026-03-28T07:15:00"));
        entityManager.flush();
        entityManager.clear();

        LocalDateTime cutoff = LocalDateTime.parse("2026-03-28T09:30:00");

        List<Long> firstBatch = interviewSessionRepository.findTimedOutInProgressSessionIds(cutoff, PageRequest.of(0, 1));
        List<Long> fullBatch = interviewSessionRepository.findTimedOutInProgressSessionIds(cutoff, PageRequest.of(0, 10));

        assertThat(firstBatch).containsExactly(oldest.getId());
        assertThat(fullBatch).containsExactly(oldest.getId(), fallback.getId());
    }

    private ChatSession persistChat(LocalDateTime lastMessageAt) {
        ChatSession chatSession = ChatSession.builder()
                .externalId(UUID.randomUUID())
                .userId(11L)
                .domainType(ChatDomainType.INTERVIEW)
                .domainRefType(ChatDomainRefType.INTERVIEW_SESSION)
                .domainRefExternalId(UUID.randomUUID())
                .status(ChatSessionStatus.ACTIVE)
                .title("Backend")
                .lastMessageAt(lastMessageAt)
                .build();
        entityManager.persist(chatSession);
        entityManager.flush();
        return chatSession;
    }

    private InterviewSession persistSession(
            UUID chatSessionId,
            InterviewStatus status,
            LocalDateTime startTime,
            LocalDateTime deletedAt,
            LocalDateTime updatedAt
    ) {
        InterviewSession session = InterviewSession.builder()
                .externalId(UUID.randomUUID())
                .userId(11L)
                .chatSessionId(chatSessionId)
                .jobTitle("Backend")
                .difficultyLevel("MID")
                .interviewMode(InterviewMode.TEXT)
                .status(status)
                .startTime(startTime)
                .deletedAt(deletedAt)
                .build();
        entityManager.persist(session);
        entityManager.flush();
        entityManager.createNativeQuery("update interview_sessions set updated_at = ? where id = ?")
                .setParameter(1, updatedAt)
                .setParameter(2, session.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(InterviewSession.class, session.getId());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {InterviewSession.class, ChatSession.class})
    @EnableJpaRepositories(basePackageClasses = InterviewSessionRepository.class)
    static class TestJpaApplication {
    }
}
