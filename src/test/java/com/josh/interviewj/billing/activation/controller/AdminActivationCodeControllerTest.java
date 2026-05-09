package com.josh.interviewj.billing.activation.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeBatchRequest;
import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeRequest;
import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.service.ActivationCodeFormatService;
import com.josh.interviewj.billing.activation.service.ActivationCodeQueryService;
import com.josh.interviewj.billing.activation.service.ActivationCodeService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminActivationCodeControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private ActivationCodeService activationCodeService;

    @Mock
    private ActivationCodeQueryService activationCodeQueryService;

    @Mock
    private ActivationCodeFormatService activationCodeFormatService;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private AdminActivationCodeController controller;
    private MockMvc mockMvc;
    private TestingAuthenticationToken authentication;
    private User adminUser;

    @BeforeEach
    void setUp() {
        controller = new AdminActivationCodeController(
                adminAccessService,
                activationCodeService,
                activationCodeQueryService,
                activationCodeFormatService,
                runtimeSwitchService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        authentication = new TestingAuthenticationToken("admin", "n/a");
        adminUser = User.builder().id(1L).username("admin").build();
        adminUser.addRole("ADMIN");
        lenient().when(adminAccessService.requireAdmin(authentication)).thenReturn(adminUser);
    }

    @Test
    void createReturnsCreatedActivationCodeView() {
        ActivationCode activationCode = activationCode(10L, "SUBABCD1234", null);
        when(activationCodeService.generateCode(eq(1L), any(CreateActivationCodeRequest.class))).thenReturn(activationCode);
        when(activationCodeFormatService.format("SUBABCD1234")).thenReturn("SUB-ABCD-1234");

        var response = controller.create(authentication, new CreateActivationCodeRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getCode()).isEqualTo("SUB-ABCD-1234");
    }

    @Test
    void createBatchReturnsFormattedCodes() {
        UUID batchId = UUID.randomUUID();
        ActivationCode first = activationCode(1L, "CRD11112222", batchId);
        ActivationCode second = activationCode(2L, "CRD33334444", batchId);
        when(activationCodeService.generateBatch(eq(1L), any(CreateActivationCodeBatchRequest.class)))
                .thenReturn(List.of(first, second));
        when(activationCodeFormatService.format("CRD11112222")).thenReturn("CRD-1111-2222");
        when(activationCodeFormatService.format("CRD33334444")).thenReturn("CRD-3333-4444");

        var response = controller.createBatch(authentication, new CreateActivationCodeBatchRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getBatchId()).isEqualTo(batchId);
        assertThat(response.getBody().getData().getCodes()).containsExactly("CRD-1111-2222", "CRD-3333-4444");
    }

    @Test
    void createWhenActivationCapabilityDisabledThrowsBillingActivation004() {
        doThrow(new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "disabled"))
                .when(activationCodeService).generateCode(eq(1L), any(CreateActivationCodeRequest.class));

        assertThatThrownBy(() -> controller.create(authentication, new CreateActivationCodeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
    }

    @Test
    void createBatchWhenActivationCapabilityDisabledThrowsBillingActivation004() {
        doThrow(new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "disabled"))
                .when(activationCodeService).generateBatch(eq(1L), any(CreateActivationCodeBatchRequest.class));

        assertThatThrownBy(() -> controller.createBatch(authentication, new CreateActivationCodeBatchRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
    }

    @Test
    void listReturnsPagedViews() {
        OffsetDateTime createdFrom = OffsetDateTime.parse("2026-04-03T10:00:00Z");
        OffsetDateTime createdTo = OffsetDateTime.parse("2026-04-04T10:00:00+00:00");
        when(activationCodeQueryService.list(
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 4, 3, 10, 0),
                LocalDateTime.of(2026, 4, 4, 10, 0),
                PageRequest.of(0, 10)
        ))
                .thenReturn(new PageImpl<>(List.of()));

        var response = controller.list(authentication, null, null, null, null, createdFrom, createdTo, PageRequest.of(0, 10));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getContent()).isEmpty();
    }

    @Test
    void statsReturnsAggregatedCounts() {
        when(activationCodeQueryService.stats()).thenReturn(
                com.josh.interviewj.billing.activation.dto.response.ActivationCodeStatsResponse.builder()
                        .total(10)
                        .unused(5)
                        .build()
        );

        var response = controller.stats(authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getTotal()).isEqualTo(10);
    }

    @Test
    void exportSetsCsvHeadersAndDelegatesWriter() throws IOException {
        OffsetDateTime createdFrom = OffsetDateTime.parse("2026-04-03T10:00:00Z");
        OffsetDateTime createdTo = OffsetDateTime.parse("2026-04-04T10:00:00+00:00");
        doAnswer(invocation -> {
            invocation.<java.io.Writer>getArgument(6).write("id,code\n1,SUB-ABCD-1234\n");
            return null;
        }).when(activationCodeQueryService).writeCsv(
                any(),
                any(),
                any(),
                any(),
                eq(LocalDateTime.of(2026, 4, 3, 10, 0)),
                eq(LocalDateTime.of(2026, 4, 4, 10, 0)),
                any()
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.export(authentication, response, null, null, null, null, createdFrom, createdTo);

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=activation-codes.csv");
        assertThat(response.getContentAsString()).startsWith("\uFEFFid,code");
    }

    @Test
    void exportWhenActivationCapabilityDisabledThrowsBeforeAnyResponseWrite() throws Exception {
        doThrow(new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "disabled"))
                .when(runtimeSwitchService).requireActivationCodeEnabled();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.export(authentication, response, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);

        assertThat(response.getContentType()).isNull();
        assertThat(response.getHeader("Content-Disposition")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
        verify(activationCodeQueryService, never()).writeCsv(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void exportAcceptsOffsetDateTimeQueryParameters() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser);
        doAnswer(invocation -> {
            invocation.<java.io.Writer>getArgument(6).write("id,code\n");
            return null;
        }).when(activationCodeQueryService).writeCsv(
                any(),
                any(),
                any(),
                any(),
                eq(LocalDateTime.of(2026, 4, 3, 10, 0)),
                eq(LocalDateTime.of(2026, 4, 4, 10, 0)),
                any()
        );

        mockMvc.perform(get("/api/v1/admin/activation-codes/export")
                        .param("createdFrom", "2026-04-03T10:00:00Z")
                        .param("createdTo", "2026-04-04T10:00:00+00:00")
                        .principal(authentication))
                .andExpect(status().isOk());

        verify(activationCodeQueryService).writeCsv(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(LocalDateTime.of(2026, 4, 3, 10, 0)),
                eq(LocalDateTime.of(2026, 4, 4, 10, 0)),
                any()
        );
    }

    @Test
    void voidCodeDelegatesToService() {
        var response = controller.voidCode(authentication, 55L);

        verify(activationCodeService).voidCode(55L);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void voidCodeWhenActivationCapabilityDisabledThrowsBillingActivation004() {
        doThrow(new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "disabled"))
                .when(activationCodeService).voidCode(55L);

        assertThatThrownBy(() -> controller.voidCode(authentication, 55L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
    }

    private ActivationCode activationCode(Long id, String code, UUID batchId) {
        return ActivationCode.builder()
                .id(id)
                .code(code)
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .billingPlanVersionId(99L)
                .subscriptionDurationDays(30)
                .createdByUserId(1L)
                .createdAt(LocalDateTime.of(2026, 4, 3, 10, 0))
                .batchId(batchId)
                .build();
    }
}
