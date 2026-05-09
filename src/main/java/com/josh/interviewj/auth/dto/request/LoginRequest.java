package com.josh.interviewj.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Login request payload.
 */
@Data
public class LoginRequest {

    @NotBlank(message = "Username must not be blank")
    private String username;

    @Valid
    @NotNull(message = "Password envelope must not be null")
    private PasswordEnvelope passwordEnvelope;
}
