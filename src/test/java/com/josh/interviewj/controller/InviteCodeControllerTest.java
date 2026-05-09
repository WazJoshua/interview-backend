package com.josh.interviewj.controller;

import com.josh.interviewj.auth.controller.InviteCodeController;
import com.josh.interviewj.auth.dto.response.InviteCodeActorView;
import com.josh.interviewj.auth.dto.response.InviteCodeView;
import com.josh.interviewj.auth.model.InviteCodeStatus;
import com.josh.interviewj.auth.service.InviteCodeService;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InviteCodeControllerTest {

    @Mock
    private InviteCodeService inviteCodeService;

    @InjectMocks
    private InviteCodeController inviteCodeController;

    private MockMvc mockMvc;
    private Authentication authentication;
    private InviteCodeView inviteCodeView;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(inviteCodeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authentication = new UsernamePasswordAuthenticationToken("admin_user", "N/A");
        inviteCodeView = InviteCodeView.builder()
                .id(UUID.randomUUID())
                .code("ABCD-WXYZ-2345")
                .status(InviteCodeStatus.UNUSED)
                .createdAt(LocalDateTime.of(2026, 3, 26, 10, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                .usedAt(null)
                .createdBy(InviteCodeActorView.builder()
                        .id(UUID.randomUUID())
                        .username("admin_user")
                        .nickname("管理员")
                        .build())
                .usedBy(null)
                .build();
    }

    @Test
    void createInviteCode_Success_ReturnsCreatedContract() throws Exception {
        when(inviteCodeService.createInviteCode(authentication)).thenReturn(inviteCodeView);

        mockMvc.perform(post("/api/v1/invite-codes")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/invite-codes/" + inviteCodeView.getId()))
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value(inviteCodeView.getId().toString()))
                .andExpect(jsonPath("$.data.code").value("ABCD-WXYZ-2345"))
                .andExpect(jsonPath("$.data.status").value("UNUSED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-03-26T10:00:00"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-04-02T10:00:00"))
                .andExpect(jsonPath("$.data.usedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.createdBy.id").value(inviteCodeView.getCreatedBy().getId().toString()))
                .andExpect(jsonPath("$.data.createdBy.username").value("admin_user"))
                .andExpect(jsonPath("$.data.createdBy.nickname").value("管理员"))
                .andExpect(jsonPath("$.data.createdBy.email").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy.phone").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.usedBy").value(nullValue()));

        verify(inviteCodeService).createInviteCode(authentication);
    }

    @Test
    void listInviteCodes_Success_ReturnsPagedPayload() throws Exception {
        Page<InviteCodeView> page = new PageImpl<>(List.of(inviteCodeView), PageRequest.of(0, 20), 1);
        when(inviteCodeService.listInviteCodes(eq(authentication), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/invite-codes")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(inviteCodeView.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].code").value("ABCD-WXYZ-2345"))
                .andExpect(jsonPath("$.data.content[0].createdBy.id").value(inviteCodeView.getCreatedBy().getId().toString()))
                .andExpect(jsonPath("$.data.content[0].createdBy.username").value("admin_user"))
                .andExpect(jsonPath("$.data.content[0].createdBy.nickname").value("管理员"))
                .andExpect(jsonPath("$.data.content[0].usedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].usedBy").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].createdBy.email").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].createdBy.phone").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].createdBy.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.pageable.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageable.pageSize").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(true))
                .andExpect(jsonPath("$.data.empty").value(false));
    }

    @Test
    void listInviteCodes_NegativePage_ReturnsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/invite-codes")
                        .principal(authentication)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void listInviteCodes_ZeroSize_ReturnsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/invite-codes")
                        .principal(authentication)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void listInviteCodes_TooLargeSize_ReturnsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/invite-codes")
                        .principal(authentication)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }
}
