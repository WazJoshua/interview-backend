package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.service.InviteCodeAccessService;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteCodeAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InviteCodeAccessService inviteCodeAccessService;

    private User inviterUser;

    @BeforeEach
    void setUp() {
        inviterUser = User.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .username("inviter")
                .email("inviter@example.com")
                .password("hashed")
                .nickname("邀请人")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build();
        inviterUser.addRole("INVITER");
    }

    @Test
    void requireInviteCodeManager_NullAuthentication_ThrowsAuth001() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> inviteCodeAccessService.requireInviteCodeManager(null));

        assertEquals("AUTH_001", exception.getErrorCode());
    }

    @Test
    void requireInviteCodeManager_AnonymousAuthentication_ThrowsAuth001() {
        AnonymousAuthenticationToken authentication = new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inviteCodeAccessService.requireInviteCodeManager(authentication));

        assertEquals("AUTH_001", exception.getErrorCode());
    }

    @Test
    void requireInviteCodeManager_BlankUsername_ThrowsAuth001() {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated("   ", "N/A", AuthorityUtils.NO_AUTHORITIES);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inviteCodeAccessService.requireInviteCodeManager(authentication));

        assertEquals("AUTH_001", exception.getErrorCode());
    }

    @Test
    void requireInviteCodeManager_InviterRole_ReturnsUser() {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated("inviter", "N/A", AuthorityUtils.NO_AUTHORITIES);
        when(userRepository.findByUsername("inviter")).thenReturn(Optional.of(inviterUser));

        User result = inviteCodeAccessService.requireInviteCodeManager(authentication);

        assertEquals(inviterUser.getId(), result.getId());
    }
}
