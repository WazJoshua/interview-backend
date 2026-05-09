package com.josh.interviewj.user.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserAccessService {

    private final UserRepository userRepository;

    public User requireVisibleUser(UUID targetUserId, String currentUsername) {
        if (targetUserId == null || currentUsername == null || currentUsername.isBlank()) {
            throw notFound();
        }

        User currentUser = userRepository.findByUsername(currentUsername)
                .orElseThrow(this::notFound);
        User targetUser = userRepository.findByExternalId(targetUserId)
                .orElseThrow(this::notFound);

        if (isSelf(currentUser, targetUser) || currentUser.getRoleNames().contains("ADMIN")) {
            return targetUser;
        }
        throw notFound();
    }

    private boolean isSelf(User currentUser, User targetUser) {
        return currentUser.getExternalId() != null && currentUser.getExternalId().equals(targetUser.getExternalId());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.USER_003, "User not found");
    }
}
