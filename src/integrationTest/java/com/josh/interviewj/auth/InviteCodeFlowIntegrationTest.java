package com.josh.interviewj.auth;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.dto.request.RegisterRequest;
import com.josh.interviewj.auth.model.InviteCode;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.InviteCodeRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.service.AuthService;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InviteCodeFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        inviteCodeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @AfterEach
    void tearDown() {
        inviteCodeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void registerConfig_AnonymousRequest_IsReadable() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.inviteCodeRequired").value(false))
                .andExpect(jsonPath("$.data.inviteCodeFormat.length").value(12))
                .andExpect(jsonPath("$.data.inviteCodeFormat.displayPattern").value("XXXX-XXXX-XXXX"));
    }

    @Test
    void inviteListScope_AdminSeesAllAndInviterSeesOwnOnly() throws Exception {
        User admin = createUser("invite-admin", "invite-admin@example.com", "ADMIN");
        User inviterA = createUser("invite-a", "invite-a@example.com", "INVITER");
        User inviterB = createUser("invite-b", "invite-b@example.com", "INVITER");

        createInviteCode(inviterA, "ABCDWXYZ2345", LocalDateTime.now().plusDays(7));
        createInviteCode(inviterB, "LMNPQRST6789", LocalDateTime.now().plusDays(7));

        mockMvc.perform(get("/api/v1/invite-codes")
                        .with(SecurityMockMvcRequestPostProcessors.user(admin.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get("/api/v1/invite-codes")
                        .with(SecurityMockMvcRequestPostProcessors.user(inviterA.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].createdBy.username").value(inviterA.getUsername()));
    }

    @Test
    void registerWithSameInviteCode_ConcurrentCalls_OnlyOneSucceeds() throws Exception {
        User inviter = createUser("invite-owner", "invite-owner@example.com", "INVITER");
        createInviteCode(inviter, "ZXCVBNMASDFG", LocalDateTime.now().plusDays(7));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<RegistrationResult>> futures = new ArrayList<>();
        futures.add(executorService.submit(() -> registerAfterLatch(startLatch, "invite-race-a", "invite-race-a@example.com")));
        futures.add(executorService.submit(() -> registerAfterLatch(startLatch, "invite-race-b", "invite-race-b@example.com")));

        startLatch.countDown();

        List<RegistrationResult> results = new ArrayList<>();
        for (Future<RegistrationResult> future : futures) {
            results.add(future.get());
        }
        executorService.shutdownNow();

        long successCount = results.stream().filter(RegistrationResult::success).count();
        long inviteUsedCount = results.stream()
                .filter(result -> !result.success() && "INVITE_003".equals(result.errorCode()))
                .count();

        assertEquals(1, successCount);
        assertEquals(1, inviteUsedCount);
        assertEquals(1, countUsersByUsernames("invite-race-a", "invite-race-b"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_codes WHERE code_normalized = ? AND used_by_user_id IS NOT NULL",
                Integer.class,
                "ZXCVBNMASDFG"
        ));
    }

    @Test
    void registerRollback_DoesNotLeaveInviteCodeConsumed() {
        User inviter = createUser("invite-rollback-owner", "invite-rollback-owner@example.com", "INVITER");
        createInviteCode(inviter, "QWERASDFZXCV", LocalDateTime.now().plusDays(7));

        RegisterRequest request = new RegisterRequest();
        request.setUsername("invite-rollback-user");
        request.setEmail("invite-rollback-user@example.com");
        request.setPasswordEnvelope(passwordEnvelope("Password123"));
        request.setNickname("回滚用户");
        request.setInviteCode("QWER-ASDF-ZXCV");

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            authService.register(request);
            throw new RuntimeException("force rollback after invite assignment");
        }));

        assertEquals(0, countUsersByUsernames("invite-rollback-user"));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT used_by_user_id FROM invite_codes WHERE code_normalized = ?",
                Long.class,
                "QWERASDFZXCV"
        ));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT used_at FROM invite_codes WHERE code_normalized = ?",
                LocalDateTime.class,
                "QWERASDFZXCV"
        ));
    }

    private RegistrationResult registerAfterLatch(CountDownLatch startLatch, String username, String email) throws Exception {
        startLatch.await();
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPasswordEnvelope(passwordEnvelope("Password123"));
        request.setNickname(username);
        request.setInviteCode("ZXCV-BNMA-SDFG");

        try {
            authService.register(request);
            return new RegistrationResult(true, null);
        } catch (BusinessException exception) {
            return new RegistrationResult(false, exception.getErrorCode());
        }
    }

    private User createUser(String username, String email, String role) {
        User user = User.builder()
                .username(username)
                .email(email)
                .password("hashed-password")
                .nickname(username)
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build();
        User savedUser = userRepository.saveAndFlush(user);
        savedUser.addRole(role);
        return userRepository.save(savedUser);
    }

    private InviteCode createInviteCode(User createdBy, String codeNormalized, LocalDateTime expiresAt) {
        return inviteCodeRepository.saveAndFlush(InviteCode.builder()
                .codeNormalized(codeNormalized)
                .createdByUserId(createdBy.getId())
                .expiresAt(expiresAt)
                .build());
    }

    private int countUsersByUsernames(String... usernames) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(usernames.length, "?"));
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username IN (" + placeholders + ")",
                Integer.class,
                (Object[]) usernames
        );
    }

    private record RegistrationResult(boolean success, String errorCode) {
    }
}
