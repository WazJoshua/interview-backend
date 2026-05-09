package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.user.service.UserAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserAccessService userAccessService;

    @Test
    void requireVisibleUser_SelfAccess_ReturnsTargetUser() {
        UUID targetUserId = UUID.randomUUID();
        User currentUser = User.builder()
                .externalId(targetUserId)
                .username("owner")
                .build();
        User targetUser = User.builder()
                .externalId(targetUserId)
                .username("owner")
                .build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(currentUser));
        when(userRepository.findByExternalId(targetUserId)).thenReturn(Optional.of(targetUser));

        User visibleUser = userAccessService.requireVisibleUser(targetUserId, "owner");

        assertSame(targetUser, visibleUser);
    }

    @Test
    void requireVisibleUser_AdminAccessOtherUser_ReturnsTargetUser() {
        UUID targetUserId = UUID.randomUUID();
        User admin = User.builder()
                .externalId(UUID.randomUUID())
                .username("admin")
                .build();
        admin.addRole("ADMIN");
        User targetUser = User.builder()
                .externalId(targetUserId)
                .username("target")
                .build();

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByExternalId(targetUserId)).thenReturn(Optional.of(targetUser));

        User visibleUser = userAccessService.requireVisibleUser(targetUserId, "admin");

        assertSame(targetUser, visibleUser);
    }

    @Test
    void requireVisibleUser_OtherUserAccess_ThrowsUser003() {
        UUID targetUserId = UUID.randomUUID();
        User currentUser = User.builder()
                .externalId(UUID.randomUUID())
                .username("viewer")
                .build();
        User targetUser = User.builder()
                .externalId(targetUserId)
                .username("target")
                .build();

        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(currentUser));
        when(userRepository.findByExternalId(targetUserId)).thenReturn(Optional.of(targetUser));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userAccessService.requireVisibleUser(targetUserId, "viewer"));

        assertEquals("USER_003", exception.getErrorCode());
    }

    @Test
    void requireVisibleUser_CurrentUserMissing_ThrowsUser003() {
        UUID targetUserId = UUID.randomUUID();

        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userAccessService.requireVisibleUser(targetUserId, "missing"));

        assertEquals("USER_003", exception.getErrorCode());
    }

    @Test
    void requireVisibleUser_TargetUserMissing_ThrowsUser003() {
        UUID targetUserId = UUID.randomUUID();
        User currentUser = User.builder()
                .externalId(targetUserId)
                .username("owner")
                .build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(currentUser));
        when(userRepository.findByExternalId(targetUserId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userAccessService.requireVisibleUser(targetUserId, "owner"));

        assertEquals("USER_003", exception.getErrorCode());
    }
}
