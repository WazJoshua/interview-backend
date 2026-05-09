package com.josh.interviewj.user.dto.request;

import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Password change request payload.
 */
@Data
public class ChangePasswordRequest {

    @Valid
    @NotNull(message = "Old password envelope must not be null")
    private PasswordEnvelope oldPasswordEnvelope;

    @Valid
    @NotNull(message = "New password envelope must not be null")
    private PasswordEnvelope newPasswordEnvelope;
}
