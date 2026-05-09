package com.josh.interviewj.service;

import com.josh.interviewj.auth.config.InviteCodeProperties;
import com.josh.interviewj.auth.dto.request.InviteCodeQueryRequest;
import com.josh.interviewj.auth.dto.response.InviteCodeView;
import com.josh.interviewj.auth.model.InviteCode;
import com.josh.interviewj.auth.model.InviteCodeStatus;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.InviteCodeRepository;
import com.josh.interviewj.auth.service.InviteCodeAccessService;
import com.josh.interviewj.auth.service.InviteCodeService;
import com.josh.interviewj.auth.support.InviteCodeCodec;
import com.josh.interviewj.common.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    @Mock
    private InviteCodeProperties inviteCodeProperties;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private InviteCodeCodec inviteCodeCodec;

    @Mock
    private InviteCodeAccessService inviteCodeAccessService;

    @Mock
    private Clock clock;

    @InjectMocks
    private InviteCodeService inviteCodeService;

    private Authentication authentication;
    private User adminUser;
    private User inviterUser;

    @BeforeEach
    void setUp() {
        authentication = new UsernamePasswordAuthenticationToken("admin_user", "N/A");

        adminUser = User.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .username("admin_user")
                .nickname("管理员")
                .email("admin@example.com")
                .password("hashed")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build();
        adminUser.addRole("ADMIN");

        inviterUser = User.builder()
                .id(2L)
                .externalId(UUID.randomUUID())
                .username("inviter_user")
                .nickname("邀请人")
                .email("inviter@example.com")
                .password("hashed")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build();
        inviterUser.addRole("INVITER");

        lenient().when(inviteCodeProperties.getTtl()).thenReturn(Duration.ofDays(7));
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-03-26T10:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(inviteCodeAccessService.canViewAllInviteCodes(adminUser)).thenReturn(true);
        lenient().when(inviteCodeAccessService.canViewAllInviteCodes(inviterUser)).thenReturn(false);
    }

    @Test
    void createInviteCode_Success_ReturnsView() {
        when(inviteCodeAccessService.requireInviteCodeManager(authentication)).thenReturn(adminUser);
        when(inviteCodeCodec.generateCanonicalCode()).thenReturn("ABCDWXYZ2345");
        when(inviteCodeCodec.formatForDisplay("ABCDWXYZ2345")).thenReturn("ABCD-WXYZ-2345");
        when(inviteCodeCodec.calculateStatus(eq(null), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(InviteCodeStatus.UNUSED);
        when(inviteCodeRepository.saveAndFlush(any(InviteCode.class))).thenAnswer(invocation -> {
            InviteCode inviteCode = invocation.getArgument(0);
            inviteCode.setId(11L);
            inviteCode.setExternalId(UUID.randomUUID());
            inviteCode.setCreatedAt(LocalDateTime.of(2026, 3, 26, 10, 0));
            inviteCode.setUpdatedAt(LocalDateTime.of(2026, 3, 26, 10, 0));
            return inviteCode;
        });

        InviteCodeView result = inviteCodeService.createInviteCode(authentication);

        assertNotNull(result);
        assertEquals("ABCD-WXYZ-2345", result.getCode());
        assertEquals(InviteCodeStatus.UNUSED, result.getStatus());
        assertEquals(adminUser.getExternalId(), result.getCreatedBy().getId());
        assertEquals("admin_user", result.getCreatedBy().getUsername());
    }

    @Test
    void createInviteCode_DuplicateCode_RetriesAndSucceeds() {
        when(inviteCodeAccessService.requireInviteCodeManager(authentication)).thenReturn(adminUser);
        when(inviteCodeCodec.generateCanonicalCode()).thenReturn("AAAAWXYZ2345", "BBBBWXYZ2345");
        when(inviteCodeCodec.formatForDisplay("BBBBWXYZ2345")).thenReturn("BBBB-WXYZ-2345");
        when(inviteCodeCodec.calculateStatus(eq(null), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(InviteCodeStatus.UNUSED);
        when(inviteCodeRepository.saveAndFlush(any(InviteCode.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate",
                        new ConstraintViolationException("duplicate", new SQLException("duplicate"), "uq_invite_codes_code_normalized")
                ))
                .thenAnswer(invocation -> {
                    InviteCode inviteCode = invocation.getArgument(0);
                    inviteCode.setId(12L);
                    inviteCode.setExternalId(UUID.randomUUID());
                    inviteCode.setCreatedAt(LocalDateTime.of(2026, 3, 26, 10, 0));
                    inviteCode.setUpdatedAt(LocalDateTime.of(2026, 3, 26, 10, 0));
                    return inviteCode;
                });

        InviteCodeView result = inviteCodeService.createInviteCode(authentication);

        assertEquals("BBBB-WXYZ-2345", result.getCode());
        verify(inviteCodeCodec, times(2)).generateCanonicalCode();
        verify(inviteCodeRepository, times(2)).saveAndFlush(any(InviteCode.class));
    }

    @Test
    void listInviteCodes_AdminWithoutStatus_QueriesAll() {
        InviteCodeQueryRequest request = new InviteCodeQueryRequest();
        InviteCode inviteCode = inviteCode("ABCDWXYZ2345", adminUser, null);
        Page<InviteCode> page = new PageImpl<>(List.of(inviteCode), PageRequest.of(0, 20), 1);

        when(inviteCodeAccessService.requireInviteCodeManager(authentication)).thenReturn(adminUser);
        when(inviteCodeRepository.findAll(PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("createdAt").descending())))
                .thenReturn(page);
        when(inviteCodeCodec.formatForDisplay("ABCDWXYZ2345")).thenReturn("ABCD-WXYZ-2345");
        when(inviteCodeCodec.calculateStatus(eq(null), eq(inviteCode.getExpiresAt()), any(LocalDateTime.class)))
                .thenReturn(InviteCodeStatus.UNUSED);

        Page<InviteCodeView> result = inviteCodeService.listInviteCodes(authentication, request);

        assertEquals(1, result.getTotalElements());
        assertEquals("ABCD-WXYZ-2345", result.getContent().getFirst().getCode());
    }

    @Test
    void listInviteCodes_InviterWithoutStatus_QueriesOwnScope() {
        InviteCodeQueryRequest request = new InviteCodeQueryRequest();
        InviteCode inviteCode = inviteCode("ABCDWXYZ2345", inviterUser, null);
        Page<InviteCode> page = new PageImpl<>(List.of(inviteCode), PageRequest.of(0, 20), 1);

        when(inviteCodeAccessService.requireInviteCodeManager(authentication)).thenReturn(inviterUser);
        when(inviteCodeRepository.findByCreatedByUserId(
                eq(inviterUser.getId()),
                eq(PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("createdAt").descending()))
        )).thenReturn(page);
        when(inviteCodeCodec.formatForDisplay("ABCDWXYZ2345")).thenReturn("ABCD-WXYZ-2345");
        when(inviteCodeCodec.calculateStatus(eq(null), eq(inviteCode.getExpiresAt()), any(LocalDateTime.class)))
                .thenReturn(InviteCodeStatus.UNUSED);

        Page<InviteCodeView> result = inviteCodeService.listInviteCodes(authentication, request);

        assertEquals(1, result.getTotalElements());
        verify(inviteCodeRepository).findByCreatedByUserId(
                eq(inviterUser.getId()),
                eq(PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("createdAt").descending()))
        );
    }

    @Test
    void createInviteCode_UserWithoutPermission_ThrowsAuth006() {
        when(inviteCodeAccessService.requireInviteCodeManager(authentication))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inviteCodeService.createInviteCode(authentication));

        assertEquals("AUTH_006", exception.getErrorCode());
    }

    private InviteCode inviteCode(String codeNormalized, User createdByUser, User usedByUser) {
        return InviteCode.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .codeNormalized(codeNormalized)
                .createdByUserId(createdByUser.getId())
                .usedByUserId(usedByUser == null ? null : usedByUser.getId())
                .createdAt(LocalDateTime.of(2026, 3, 26, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 3, 26, 10, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                .usedAt(usedByUser == null ? null : LocalDateTime.of(2026, 3, 26, 11, 0))
                .createdByUser(createdByUser)
                .usedByUser(usedByUser)
                .build();
    }
}
