package com.josh.interviewj.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Public user profile representation returned by authentication APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private UUID id;
    private String username;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private Set<String> roles;
    private String locale;
    private String timezone;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
