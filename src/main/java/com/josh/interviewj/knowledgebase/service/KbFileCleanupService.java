package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTask;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTaskStatus;
import com.josh.interviewj.knowledgebase.repository.KbFileCleanupTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Queues and drains deferred storage cleanup tasks for knowledge base files.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KbFileCleanupService {
    private static final long INITIAL_DRAIN_ELIGIBILITY_BUFFER_SECONDS = 1L;

    private static final List<KbFileCleanupTaskStatus> READY_STATUSES = List.of(
            KbFileCleanupTaskStatus.PENDING,
            KbFileCleanupTaskStatus.RETRY
    );
    private static final List<KbFileCleanupTaskStatus> OBSERVABLE_BACKLOG_STATUSES = List.of(
            KbFileCleanupTaskStatus.PENDING,
            KbFileCleanupTaskStatus.RETRY,
            KbFileCleanupTaskStatus.FAILED
    );

    private final KbFileCleanupTaskRepository kbFileCleanupTaskRepository;
    private final KnowledgeBaseStorageService knowledgeBaseStorageService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Enqueues a cleanup task for a single document file.
     *
     * @param document document whose file should be deleted
     * @return created cleanup tasks
     */
    public List<KbFileCleanupTask> enqueueDocumentFile(KbDocument document) {
        if (document.getFileUrl() == null || document.getFileUrl().isBlank()) {
            return List.of();
        }
        return kbFileCleanupTaskRepository.saveAllAndFlush(List.of(buildTask("DOCUMENT", document.getId(), document.getFileUrl())));
    }

    /**
     * Enqueues cleanup tasks for all stored files that belong to a deleted knowledge base.
     *
     * @param kbId knowledge base primary key
     * @param documents documents selected for cleanup
     * @return created cleanup tasks
     */
    public List<KbFileCleanupTask> enqueueKnowledgeBaseFiles(Long kbId, List<KbDocument> documents) {
        List<KbFileCleanupTask> tasks = documents.stream()
                .filter(document -> document.getFileUrl() != null && !document.getFileUrl().isBlank())
                .map(document -> buildTask("KNOWLEDGE_BASE", kbId, document.getFileUrl()))
                .toList();
        if (tasks.isEmpty()) {
            return List.of();
        }
        return kbFileCleanupTaskRepository.saveAllAndFlush(tasks);
    }

    /**
     * Drains one batch of ready cleanup tasks and updates their retry state.
     *
     * @param pageable batch size and ordering
     * @return drain result snapshot
     */
    public DrainResult drainReadyTasks(Pageable pageable) {
        return transactionTemplate.execute(status -> doDrainReadyTasks(pageable));
    }

    /**
     * Drains an explicit batch of cleanup tasks, ignoring next-attempt timing because
     * the caller already selected the exact tasks that should be processed now.
     *
     * @param taskIds task ids to process
     * @return drain result snapshot
     */
    public DrainResult drainTasks(Collection<Long> taskIds) {
        return transactionTemplate.execute(status -> doDrainTasks(taskIds));
    }

    private DrainResult doDrainReadyTasks(Pageable pageable) {
        List<KbFileCleanupTask> tasks = kbFileCleanupTaskRepository
                .findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        READY_STATUSES,
                        LocalDateTime.now(),
                        pageable
                );
        return doDrainTasks(tasks);
    }

    private DrainResult doDrainTasks(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new DrainResult(0, 0, countBacklog());
        }
        List<KbFileCleanupTask> tasks = new ArrayList<>(kbFileCleanupTaskRepository.findAllById(taskIds));
        tasks.sort(Comparator
                .comparing(KbFileCleanupTask::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(KbFileCleanupTask::getId, Comparator.nullsLast(Long::compareTo)));
        return doDrainTasks(tasks);
    }

    private DrainResult doDrainTasks(List<KbFileCleanupTask> tasks) {
        int successCount = 0;
        int failureCount = 0;
        for (KbFileCleanupTask task : tasks) {
            if (!READY_STATUSES.contains(task.getStatus())) {
                continue;
            }
            try {
                knowledgeBaseStorageService.delete(task.getStorageKey());
                task.setStatus(KbFileCleanupTaskStatus.COMPLETED);
                task.setCompletedAt(LocalDateTime.now());
                task.setLastError(null);
                successCount++;
            } catch (BusinessException ex) {
                failureCount++;
                applyFailure(task, ex.getMessage());
            }
            kbFileCleanupTaskRepository.save(task);
        }
        long backlog = countBacklog();
        log.info("KB file cleanup drain finished: successCount={}, failureCount={}, backlog={}", successCount, failureCount, backlog);
        return new DrainResult(successCount, failureCount, backlog);
    }

    /**
     * Counts cleanup tasks that still need retry or manual attention.
     *
     * @return observable backlog size
     */
    public long countBacklog() {
        return kbFileCleanupTaskRepository.countByStatusIn(OBSERVABLE_BACKLOG_STATUSES);
    }

    /**
     * Schedules one cleanup drain after the surrounding transaction commits.
     *
     * @param pageable batch size and ordering
     */
    public void scheduleDrainAfterCommit(Pageable pageable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            drainReadyTasks(pageable);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                drainReadyTasks(pageable);
            }
        });
    }

    /**
     * Schedules an immediate drain for a known set of cleanup tasks after commit.
     *
     * @param taskIds ids of tasks created in the surrounding transaction
     */
    public void scheduleDrainAfterCommit(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        List<Long> snapshotTaskIds = taskIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (snapshotTaskIds.isEmpty()) {
            scheduleDrainAfterCommit(org.springframework.data.domain.PageRequest.of(0, Math.max(50, taskIds.size())));
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            drainTasks(snapshotTaskIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                drainTasks(snapshotTaskIds);
            }
        });
    }

    /**
     * Creates a new cleanup task entity with initial retry metadata.
     *
     * @param resourceType logical cleanup owner type
     * @param resourceRefId logical owner id
     * @param storageKey storage key to delete
     * @return initialized cleanup task
     */
    private KbFileCleanupTask buildTask(String resourceType, Long resourceRefId, String storageKey) {
        return KbFileCleanupTask.builder()
                .resourceType(resourceType)
                .resourceRefId(resourceRefId)
                .storageKey(storageKey)
                .status(KbFileCleanupTaskStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(INITIAL_DRAIN_ELIGIBILITY_BUFFER_SECONDS))
                .build();
    }

    /**
     * Advances retry state after one failed delete attempt.
     *
     * @param task cleanup task to update
     * @param errorMessage safe error message from storage deletion
     */
    private void applyFailure(KbFileCleanupTask task, String errorMessage) {
        int nextAttemptCount = task.getAttemptCount() + 1;
        task.setAttemptCount(nextAttemptCount);
        task.setLastError(errorMessage);
        if (nextAttemptCount >= 5) {
            task.setStatus(KbFileCleanupTaskStatus.FAILED);
            return;
        }
        task.setStatus(KbFileCleanupTaskStatus.RETRY);
        task.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes(nextAttemptCount)));
    }

    /**
     * Returns the deterministic retry backoff in minutes for the given attempt count.
     *
     * @param attemptCount current attempt count after increment
     * @return next retry backoff in minutes
     */
    private long backoffMinutes(int attemptCount) {
        return switch (attemptCount) {
            case 1 -> 1L;
            case 2 -> 5L;
            case 3 -> 15L;
            default -> 60L;
        };
    }

    /**
     * Summarizes one drain execution result for logging and tests.
     *
     * @param successCount number of tasks completed during this run
     * @param failureCount number of tasks that failed during this run
     * @param backlog remaining observable backlog after this run
     */
    public record DrainResult(int successCount, int failureCount, long backlog) {
    }
}
