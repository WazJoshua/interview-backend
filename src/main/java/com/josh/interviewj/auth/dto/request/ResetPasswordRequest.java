package com.josh.interviewj.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Password reset completion request.
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "Reset token must not be blank")
    private String resetToken;

    @Valid
    @NotNull(message = "New password envelope must not be null")
    private PasswordEnvelope newPasswordEnvelope;
}
