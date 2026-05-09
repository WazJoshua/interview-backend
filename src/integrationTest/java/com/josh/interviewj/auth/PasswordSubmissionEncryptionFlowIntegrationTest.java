package com.josh.interviewj.auth;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.support.InMemoryPasswordResetNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestPasswordResetNotificationConfig.class)
class PasswordSubmissionEncryptionFlowIntegrationTest extends IntegrationTestBase {

    private static final String USERNAME = "crypto_user";
    private static final String EMAIL = "crypto-user@example.com";
    private static final String NICKNAME = "crypto-user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryPasswordResetNotificationService inMemoryPasswordResetNotificationService;

    @BeforeEach
    void setUp() {
        inMemoryPasswordResetNotificationService.clear();
        jdbcTemplate.execute("DELETE FROM password_reset_tokens");
        jdbcTemplate.execute("DELETE FROM invite_codes");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM password_reset_tokens");
        jdbcTemplate.execute("DELETE FROM invite_codes");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    void fullPasswordEnvelopeFlow_WorksEndToEnd() throws Exception {
        String registerPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "username", USERNAME,
                "email", EMAIL,
                "nickname", NICKNAME,
                "passwordEnvelope", passwordEnvelope("Password123")
        ));

        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(201))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode registeredUser = objectMapper.readTree(registerResponse).path("data");
        UUID userId = UUID.fromString(registeredUser.path("id").asText());

        String loginPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "username", USERNAME,
                "passwordEnvelope", passwordEnvelope("Password123", "nonce-login-replay")
        ));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(userId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(loginResponse).path("data").path("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("AUTH_007"))
                .andExpect(jsonPath("$.error.details[0].code").value("nonce_reused"));

        String changePasswordPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "oldPasswordEnvelope", passwordEnvelope("Password123"),
                "newPasswordEnvelope", passwordEnvelope("Password456")
        ));

        mockMvc.perform(put("/api/v1/users/{userId}/password", userId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(changePasswordPayload))
                .andExpect(status().isNoContent());

        String reloginPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "username", USERNAME,
                "passwordEnvelope", passwordEnvelope("Password456")
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(reloginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(userId.toString()));

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s"
                                }
                                """.formatted(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset email accepted"));

        assertEquals(1, inMemoryPasswordResetNotificationService.size());
        String rawResetToken = inMemoryPasswordResetNotificationService.latest().rawToken();

        String resetPasswordPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "resetToken", rawResetToken,
                "newPasswordEnvelope", passwordEnvelope("Password789")
        ));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content(resetPasswordPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successful"));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content(resetPasswordPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("AUTH_008"));

        String finalLoginPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "username", USERNAME,
                "passwordEnvelope", passwordEnvelope("Password789")
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(finalLoginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(userId.toString()));
    }
}
