package com.josh.interviewj.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Password reset initiation request.
 */
@Data
public class ForgotPasswordRequest {

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Invalid email format")
    private String email;
}
