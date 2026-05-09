package com.josh.interviewj.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Encrypted password payload submitted by clients.
 */
@Data
public class PasswordEnvelope {

    @NotBlank(message = "Password keyId must not be blank")
    private String keyId;

    @NotBlank(message = "Password nonce must not be blank")
    private String nonce;

    @NotNull(message = "Password timestamp must not be null")
    private Long timestamp;

    @NotBlank(message = "Password ciphertext must not be blank")
    private String ciphertext;
}
