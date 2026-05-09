package com.josh.interviewj.auth.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.dto.request.UpdateUserRoleFlagsRequest;
import com.josh.interviewj.auth.dto.response.AdminUserRoleFlagsResponse;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.model.UserRole;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.repository.UserRoleRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserAccessService {

    private static final String INVITER_ROLE = "INVITER";
    private static final String USER_ROLE_UNIQUE_CONSTRAINT = "uq_user_role";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final AdminOperationLogService adminOperationLogService;

    public AdminUserRoleFlagsResponse getUserRoleFlags(UUID userExternalId) {
        User user = userRepository.findByExternalId(userExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_003, "User not found"));
        return toResponse(user.getExternalId(), user.getRoleNames());
    }

    @Transactional
    public AdminUserRoleFlagsResponse updateUserRoleFlags(
            Long actorUserId,
            UUID userExternalId,
            UpdateUserRoleFlagsRequest request
    ) {
        User user = userRepository.findByExternalId(userExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_003, "User not found"));

        Set<String> beforeRoles = sortedRoles(user.getRoleNames());
        AdminUserRoleFlagsResponse beforeSnapshot = toResponse(user.getExternalId(), beforeRoles);
        Set<String> afterRoles = new TreeSet<>(beforeRoles);

        if (Boolean.TRUE.equals(request.getInviter())) {
            grantInviterRole(user);
            afterRoles.add(INVITER_ROLE);
        } else {
            revokeInviterRole(user);
            afterRoles.remove(INVITER_ROLE);
        }

        AdminUserRoleFlagsResponse response = toResponse(user.getExternalId(), afterRoles);
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.USER_ROLE_FLAGS,
                userExternalId.toString(),
                null,
                beforeSnapshot,
                response,
                Map.of("targetUserId", userExternalId.toString())
        );
        return response;
    }

    private void grantInviterRole(User user) {
        if (userRoleRepository.existsByUserIdAndRole(user.getId(), INVITER_ROLE)) {
            return;
        }
        try {
            userRoleRepository.saveAndFlush(UserRole.builder()
                    .userId(user.getId())
                    .role(INVITER_ROLE)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            if (USER_ROLE_UNIQUE_CONSTRAINT.equals(findConstraintName(exception))) {
                return;
            }
            throw exception;
        }
    }

    private void revokeInviterRole(User user) {
        if (!userRoleRepository.existsByUserIdAndRole(user.getId(), INVITER_ROLE)) {
            return;
        }
        userRoleRepository.deleteByUserIdAndRole(user.getId(), INVITER_ROLE);
    }

    private AdminUserRoleFlagsResponse toResponse(UUID userExternalId, Set<String> roles) {
        List<String> sortedRoles = sortedRoles(roles).stream().toList();
        return AdminUserRoleFlagsResponse.builder()
                .userId(userExternalId.toString())
                .roles(sortedRoles)
                .flags(AdminUserRoleFlagsResponse.Flags.builder()
                        .inviter(sortedRoles.contains(INVITER_ROLE))
                        .build())
                .build();
    }

    private TreeSet<String> sortedRoles(Set<String> roles) {
        return new TreeSet<>(roles);
    }

    private String findConstraintName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolationException) {
                return constraintViolationException.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
