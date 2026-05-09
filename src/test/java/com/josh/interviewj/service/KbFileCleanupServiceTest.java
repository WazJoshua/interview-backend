package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTask;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTaskStatus;
import com.josh.interviewj.knowledgebase.repository.KbFileCleanupTaskRepository;
import com.josh.interviewj.knowledgebase.service.KbFileCleanupService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import org.junit.jupiter.api.BeforeEach;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbFileCleanupServiceTest {

    @Mock
    private KbFileCleanupTaskRepository kbFileCleanupTaskRepository;

    @Mock
    private KnowledgeBaseStorageService knowledgeBaseStorageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private KbFileCleanupService kbFileCleanupService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null)
        );
    }

    @Test
    void enqueueDocumentFile_NewTask_IsImmediatelyEligibleForDrain() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .fileUrl("kb/doc.txt")
                .build();
        when(kbFileCleanupTaskRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        kbFileCleanupService.enqueueDocumentFile(document);
        LocalDateTime after = LocalDateTime.now();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbFileCleanupTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(kbFileCleanupTaskRepository).saveAllAndFlush(captor.capture());
        KbFileCleanupTask task = captor.getValue().getFirst();
        assertEquals(KbFileCleanupTaskStatus.PENDING, task.getStatus());
        assertTrue(task.getNextAttemptAt().isBefore(after) || task.getNextAttemptAt().isEqual(after));
        assertTrue(task.getNextAttemptAt().isBefore(before) || task.getNextAttemptAt().isEqual(before));
    }

    @Test
    void drainReadyTasks_MaxAttemptsFailureStillCountsInBacklog() {
        KbFileCleanupTask task = KbFileCleanupTask.builder()
                .id(1L)
                .resourceType("DOCUMENT")
                .resourceRefId(10L)
                .storageKey("missing.txt")
                .status(KbFileCleanupTaskStatus.RETRY)
                .attemptCount(4)
                .nextAttemptAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(kbFileCleanupTaskRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                any(), any(), any())).thenReturn(List.of(task));
        when(kbFileCleanupTaskRepository.countByStatusIn(any())).thenReturn(1L);
        org.mockito.Mockito.doThrow(new BusinessException("FILE_001", "delete failed"))
                .when(knowledgeBaseStorageService).delete("missing.txt");

        KbFileCleanupService.DrainResult result = kbFileCleanupService.drainReadyTasks(PageRequest.of(0, 10));

        ArgumentCaptor<KbFileCleanupTask> captor = ArgumentCaptor.forClass(KbFileCleanupTask.class);
        verify(kbFileCleanupTaskRepository).save(captor.capture());
        assertEquals(KbFileCleanupTaskStatus.FAILED, captor.getValue().getStatus());
        assertEquals(1, result.failureCount());
        assertEquals(1L, result.backlog());
    }

    @Test
    void drainTasks_TargetedIds_ProcessesKnownTaskWithoutScheduleLookup() {
        KbFileCleanupTask task = KbFileCleanupTask.builder()
                .id(7L)
                .resourceType("DOCUMENT")
                .resourceRefId(10L)
                .storageKey("existing.txt")
                .status(KbFileCleanupTaskStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .build();
        when(kbFileCleanupTaskRepository.findAllById(eq(List.of(7L)))).thenReturn(List.of(task));
        when(kbFileCleanupTaskRepository.countByStatusIn(any())).thenReturn(0L);

        KbFileCleanupService.DrainResult result = kbFileCleanupService.drainTasks(List.of(7L));

        ArgumentCaptor<KbFileCleanupTask> captor = ArgumentCaptor.forClass(KbFileCleanupTask.class);
        verify(kbFileCleanupTaskRepository).save(captor.capture());
        assertEquals(KbFileCleanupTaskStatus.COMPLETED, captor.getValue().getStatus());
        assertEquals(1, result.successCount());
        assertEquals(0, result.failureCount());
        assertEquals(0L, result.backlog());
    }
}
