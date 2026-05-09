package com.josh.interviewj.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User profile response returned by the users module.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private UUID id;
    private String username;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String locale;
    private String timezone;
    private LocalDateTime createdAt;
}
