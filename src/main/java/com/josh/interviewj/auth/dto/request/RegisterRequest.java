package com.josh.interviewj.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * User registration request payload.
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "Username must not be blank")
    @Size(min = 3, max = 100, message = "Username length must be between 3 and 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers, and underscores")
    private String username;

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Invalid email format")
    private String email;

    @Valid
    @NotNull(message = "Password envelope must not be null")
    private PasswordEnvelope passwordEnvelope;

    @Size(max = 100, message = "Nickname must not exceed 100 characters")
    private String nickname;

    private String inviteCode;
}
