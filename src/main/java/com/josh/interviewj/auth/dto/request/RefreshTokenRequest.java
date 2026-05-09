package com.josh.interviewj.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Refresh token request payload.
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token must not be blank")
    private String refreshToken;
}
