package com.josh.interviewj.auth.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.dto.request.UpdateUserRoleFlagsRequest;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.repository.UserRoleRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    private AdminUserAccessService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserAccessService(userRepository, userRoleRepository, adminOperationLogService);
    }

    @Test
    void getUserRoleFlags_UserMissing_ThrowsUser003() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserRoleFlags(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_003);
    }

    @Test
    void updateUserRoleFlags_AddInviter_PersistsRoleAndWritesAudit() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId, Set.of("USER"))));
        when(userRoleRepository.existsByUserIdAndRole(10L, "INVITER")).thenReturn(false);

        UpdateUserRoleFlagsRequest request = new UpdateUserRoleFlagsRequest();
        request.setInviter(true);

        var response = service.updateUserRoleFlags(99L, userId, request);

        assertThat(response.getFlags().getInviter()).isTrue();
        assertThat(response.getRoles()).containsExactly("INVITER", "USER");
        verify(userRoleRepository).saveAndFlush(any());
        verify(adminOperationLogService).recordUpdate(
                eq(99L),
                eq(AdminOperationResourceType.USER_ROLE_FLAGS),
                eq(userId.toString()),
                eq(null),
                any(),
                any(),
                eq(java.util.Map.of("targetUserId", userId.toString()))
        );
    }

    @Test
    void updateUserRoleFlags_RemoveInviter_DeletesRoleAndWritesAudit() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId, Set.of("INVITER", "USER"))));
        when(userRoleRepository.existsByUserIdAndRole(10L, "INVITER")).thenReturn(true);

        UpdateUserRoleFlagsRequest request = new UpdateUserRoleFlagsRequest();
        request.setInviter(false);

        var response = service.updateUserRoleFlags(99L, userId, request);

        assertThat(response.getFlags().getInviter()).isFalse();
        assertThat(response.getRoles()).containsExactly("USER");
        verify(userRoleRepository).deleteByUserIdAndRole(10L, "INVITER");
        verify(adminOperationLogService).recordUpdate(
                eq(99L),
                eq(AdminOperationResourceType.USER_ROLE_FLAGS),
                eq(userId.toString()),
                eq(null),
                any(),
                any(),
                eq(java.util.Map.of("targetUserId", userId.toString()))
        );
    }

    @Test
    void updateUserRoleFlags_ConcurrentDuplicateGrant_IsTreatedAsNoOp() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId, Set.of("USER"))));
        when(userRoleRepository.existsByUserIdAndRole(10L, "INVITER")).thenReturn(false);
        when(userRoleRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate"), "uq_user_role")
        ));

        UpdateUserRoleFlagsRequest request = new UpdateUserRoleFlagsRequest();
        request.setInviter(true);

        var response = service.updateUserRoleFlags(99L, userId, request);

        assertThat(response.getFlags().getInviter()).isTrue();
        assertThat(response.getRoles()).containsExactly("INVITER", "USER");
        verify(adminOperationLogService).recordUpdate(
                eq(99L),
                eq(AdminOperationResourceType.USER_ROLE_FLAGS),
                eq(userId.toString()),
                eq(null),
                any(),
                any(),
                eq(java.util.Map.of("targetUserId", userId.toString()))
        );
    }

    @Test
    void updateUserRoleFlags_NonUniqueIntegrityViolation_IsNotTreatedAsNoOp() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId, Set.of("USER"))));
        when(userRoleRepository.existsByUserIdAndRole(10L, "INVITER")).thenReturn(false);
        when(userRoleRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "invalid role",
                new ConstraintViolationException("invalid role", new SQLException("invalid role"), "chk_user_roles_role")
        ));

        UpdateUserRoleFlagsRequest request = new UpdateUserRoleFlagsRequest();
        request.setInviter(true);

        assertThatThrownBy(() -> service.updateUserRoleFlags(99L, userId, request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(adminOperationLogService, never()).recordUpdate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateUserRoleFlags_RemoveMissingInviter_IsNoOp() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId, Set.of("USER"))));
        when(userRoleRepository.existsByUserIdAndRole(10L, "INVITER")).thenReturn(false);

        UpdateUserRoleFlagsRequest request = new UpdateUserRoleFlagsRequest();
        request.setInviter(false);

        var response = service.updateUserRoleFlags(99L, userId, request);

        assertThat(response.getFlags().getInviter()).isFalse();
        assertThat(response.getRoles()).containsExactly("USER");
        verify(userRoleRepository, never()).deleteByUserIdAndRole(10L, "INVITER");
    }

    private User user(UUID userId, Set<String> roles) {
        User user = User.builder()
                .id(10L)
                .externalId(userId)
                .username("target")
                .email("target@example.com")
                .password("hashed")
                .build();
        roles.forEach(user::addRole);
        return user;
    }
}
