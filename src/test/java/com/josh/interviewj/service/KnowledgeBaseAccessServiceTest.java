package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @InjectMocks
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    private User owner;
    private UUID kbExternalId;
    private KnowledgeBase activeKb;
    private KnowledgeBase archivedKb;
    private KnowledgeBase deletedKb;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setUsername("testuser");
        kbExternalId = UUID.randomUUID();

        activeKb = buildKnowledgeBase(KnowledgeBaseStatus.ACTIVE);
        archivedKb = buildKnowledgeBase(KnowledgeBaseStatus.ARCHIVED);
        deletedKb = buildKnowledgeBase(KnowledgeBaseStatus.DELETED);
    }

    @Test
    void activeKnowledgeBase_AllowsReadableWritableDeletableAndQueryable() {
        mockUserAndKnowledgeBase(activeKb);

        assertNotNull(knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", kbExternalId));
        assertNotNull(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", kbExternalId));
        assertNotNull(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", kbExternalId));
        assertNotNull(knowledgeBaseAccessService.requireQueryableKnowledgeBase("testuser", kbExternalId));
    }

    @Test
    void archivedKnowledgeBase_AllowsReadableAndDeletableOnly() {
        mockUserAndKnowledgeBase(archivedKb);

        assertNotNull(knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", kbExternalId));
        assertNotNull(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", kbExternalId));
        assertConflict(() -> knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", kbExternalId));
        assertConflict(() -> knowledgeBaseAccessService.requireQueryableKnowledgeBase("testuser", kbExternalId));
        assertConflict(() -> knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", kbExternalId));
        assertConflict(() -> knowledgeBaseAccessService.requireReindexableKnowledgeBase("testuser", kbExternalId));
    }

    @Test
    void deletedKnowledgeBase_ReturnsNotFoundForOwner() {
        mockUserAndKnowledgeBase(deletedKb);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", kbExternalId));

        assertEquals(ErrorCode.KB_001, exception.getErrorCode());
    }

    @Test
    void otherUsersKnowledgeBase_ReturnsForbidden() {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other");
        KnowledgeBase othersKb = buildKnowledgeBase(KnowledgeBaseStatus.ACTIVE);
        othersKb.setUserId(otherUser.getId());

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(owner));
        when(knowledgeBaseRepository.findByExternalId(kbExternalId)).thenReturn(Optional.of(othersKb));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", kbExternalId));

        assertEquals(ErrorCode.AUTH_006, exception.getErrorCode());
    }

    @Test
    void readableDocument_ResolvesWithinReadableKnowledgeBase() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(activeKb.getId())
                .externalId(UUID.randomUUID())
                .build();
        mockUserAndKnowledgeBase(activeKb);
        when(kbDocumentRepository.findByExternalIdAndKbId(document.getExternalId(), activeKb.getId()))
                .thenReturn(Optional.of(document));

        KbDocument actual = knowledgeBaseAccessService.requireReadableDocument("testuser", kbExternalId, document.getExternalId());

        assertEquals(document.getId(), actual.getId());
    }

    private void mockUserAndKnowledgeBase(KnowledgeBase knowledgeBase) {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(owner));
        when(knowledgeBaseRepository.findByExternalId(kbExternalId)).thenReturn(Optional.of(knowledgeBase));
    }

    private KnowledgeBase buildKnowledgeBase(KnowledgeBaseStatus status) {
        return KnowledgeBase.builder()
                .id(1L)
                .externalId(kbExternalId)
                .userId(owner.getId())
                .name("KB")
                .status(status)
                .build();
    }

    private void assertConflict(CheckedRunnable runnable) {
        BusinessException exception = assertThrows(BusinessException.class, runnable::run);
        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run();
    }
}
