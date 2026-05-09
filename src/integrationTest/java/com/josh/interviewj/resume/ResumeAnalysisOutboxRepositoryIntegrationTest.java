package com.josh.interviewj.resume;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisReportRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies analysis outbox repository state transitions against the real database.
 */
@SpringBootTest
class ResumeAnalysisOutboxRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeAnalysisReportRepository reportRepository;

    @Autowired
    private ResumeAnalysisOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Clears related tables before each repository scenario.
     */
    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        reportRepository.deleteAllInBatch();
        resumeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /**
     * Verifies pending outbox rows are queried in creation order.
     */
    @Test
    void findByStatusInOrderByCreatedAtAsc_ReturnsClaimableRows() {
        ResumeAnalysisOutbox first = outboxRepository.save(createOutbox(OutboxStatus.NEW, 0));
        ResumeAnalysisOutbox second = outboxRepository.save(createOutbox(OutboxStatus.RETRY, 1));

        List<ResumeAnalysisOutbox> rows = outboxRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
        );

        assertEquals(List.of(first.getId(), second.getId()), rows.stream().map(ResumeAnalysisOutbox::getId).toList());
    }

    /**
     * Verifies owner fencing transitions for claim, sent, retry, and timeout recovery.
     */
    @Test
    void stateTransitions_RespectOwnerFencingAndRecovery() {
        ResumeAnalysisOutbox outbox = outboxRepository.save(createOutbox(OutboxStatus.NEW, 0));

        assertEquals(
                1,
                outboxRepository.claimForProcessing(
                        outbox.getId(),
                        "owner-a",
                        OutboxStatus.PROCESSING,
                        List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
                )
        );

        assertEquals(
                1,
                outboxRepository.prepareRetryWithOwner(
                        outbox.getId(),
                        "owner-a",
                        OutboxStatus.RETRY,
                        1,
                        OutboxStatus.PROCESSING
                )
        );

        ResumeAnalysisOutbox retried = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.RETRY, retried.getStatus());
        assertEquals(1, retried.getRetryCount());
        assertNull(retried.getOwner());

        assertEquals(
                1,
                outboxRepository.claimForProcessing(
                        outbox.getId(),
                        "owner-b",
                        OutboxStatus.PROCESSING,
                        List.of(OutboxStatus.NEW, OutboxStatus.RETRY)
                )
        );
        assertEquals(
                1,
                outboxRepository.markAsSentWithOwner(
                        outbox.getId(),
                        "owner-b",
                        OutboxStatus.SENT,
                        LocalDateTime.now(),
                        OutboxStatus.PROCESSING
                )
        );

        ResumeAnalysisOutbox sent = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, sent.getStatus());

        ResumeAnalysisOutbox timedOut = outboxRepository.save(createOutbox(OutboxStatus.PROCESSING, 2));
        jdbcTemplate.update(
                "UPDATE resume_analysis_outbox SET owner = ?, updated_at = ? WHERE id = ?",
                "owner-timeout",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                timedOut.getId()
        );

        assertEquals(
                1,
                outboxRepository.recoverTimedOutProcessing(
                        LocalDateTime.now().minusMinutes(5),
                        OutboxStatus.RETRY,
                        OutboxStatus.PROCESSING
                )
        );

        ResumeAnalysisOutbox recovered = outboxRepository.findById(timedOut.getId()).orElseThrow();
        assertEquals(OutboxStatus.RETRY, recovered.getStatus());
        assertNull(recovered.getOwner());
    }

    /**
     * Creates an analysis outbox row tied to a persisted resume analysis report.
     *
     * @param status     initial outbox status
     * @param retryCount initial retry count
     * @return new analysis outbox entity
     */
    private ResumeAnalysisOutbox createOutbox(OutboxStatus status, int retryCount) {
        User owner = userRepository.save(User.builder()
                .username("analysis-owner-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com")
                .password("hashed")
                .build());

        Resume resume = resumeRepository.save(Resume.builder()
                .externalId(UUID.randomUUID())
                .userId(owner.getId())
                .fileName("analysis.pdf")
                .fileUrl("mock://analysis.pdf")
                .fileType("application/pdf")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .build());

        ResumeAnalysisReport report = reportRepository.save(ResumeAnalysisReport.builder()
                .resumeId(resume.getId())
                .userId(owner.getId())
                .status(AnalysisStatus.PENDING)
                .completenessScore(0)
                .clarityScore(0)
                .overallScore(0)
                .retryCount(0)
                .build());

        return ResumeAnalysisOutbox.builder()
                .reportId(report.getId())
                .resumeId(resume.getId())
                .resumeExternalId(resume.getExternalId())
                .status(status)
                .retryCount(retryCount)
                .build();
    }
}
