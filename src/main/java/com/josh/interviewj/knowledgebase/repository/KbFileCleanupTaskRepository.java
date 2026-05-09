package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.model.KbFileCleanupTask;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Accesses deferred knowledge base file cleanup tasks.
 */
@Repository
public interface KbFileCleanupTaskRepository extends JpaRepository<KbFileCleanupTask, Long> {

    /**
     * Returns cleanup tasks that are ready to be retried in created order.
     *
     * @param statuses statuses that are executable
     * @param nextAttemptAt upper bound for next attempt time
     * @param pageable batch size
     * @return ready cleanup tasks
     */
    List<KbFileCleanupTask> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
            Collection<KbFileCleanupTaskStatus> statuses,
            LocalDateTime nextAttemptAt,
            Pageable pageable
    );

    /**
     * Counts cleanup tasks that still require retry or manual attention.
     *
     * @param statuses observable backlog statuses
     * @return backlog size
     */
    long countByStatusIn(Collection<KbFileCleanupTaskStatus> statuses);
}
