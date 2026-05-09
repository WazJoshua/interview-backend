package com.josh.interviewj.auth.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final UserRepository userRepository;

    public User requireAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.AUTH_001, "Unauthorized access");
        }
        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_001, "Unauthorized access");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
        if (!user.getRoleNames().contains("ADMIN")) {
            throw new BusinessException(ErrorCode.AUTH_006, "Forbidden");
        }
        return user;
    }
}
