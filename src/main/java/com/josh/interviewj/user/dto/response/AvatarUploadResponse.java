package com.josh.interviewj.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Avatar upload response payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadResponse {

    private String avatarUrl;
}
