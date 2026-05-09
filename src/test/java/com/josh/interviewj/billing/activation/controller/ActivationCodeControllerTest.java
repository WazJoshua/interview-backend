package com.josh.interviewj.billing.activation.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.activation.dto.request.RedeemActivationCodeRequest;
import com.josh.interviewj.billing.activation.dto.response.RedeemResultResponse;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.service.ActivationCodeService;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationCodeControllerTest {

    @Mock
    private ActivationCodeService activationCodeService;

    @Mock
    private UserRepository userRepository;

    private ActivationCodeController controller;

    @BeforeEach
    void setUp() {
        controller = new ActivationCodeController(activationCodeService, userRepository);
    }

    @Test
    void redeemReturnsSuccessResponseForAuthenticatedUser() {
        User user = User.builder().id(101L).username("josh").build();
        user.addRole("USER");
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(activationCodeService.redeem(101L, "SUB-ABCD-1234"))
                .thenReturn(RedeemResultResponse.builder()
                        .codeType(ActivationCodeType.SUBSCRIPTION)
                        .grantedPlanName("Pro")
                        .build());

        RedeemActivationCodeRequest request = new RedeemActivationCodeRequest();
        request.setCode("SUB-ABCD-1234");

        var response = controller.redeem(new TestingAuthenticationToken("josh", "n/a", "ROLE_USER"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getGrantedPlanName()).isEqualTo("Pro");
    }

    @Test
    void redeemWithoutAuthenticationThrowsUnauthorized() {
        RedeemActivationCodeRequest request = new RedeemActivationCodeRequest();
        request.setCode("SUB-ABCD-1234");

        assertThatThrownBy(() -> controller.redeem(null, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("AUTH_001");
    }

    @Test
    void redeemWhenAuthenticatedUserIsMissingThrowsAuth003() {
        RedeemActivationCodeRequest request = new RedeemActivationCodeRequest();
        request.setCode("SUB-ABCD-1234");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.redeem(new TestingAuthenticationToken("ghost", "n/a", "ROLE_USER"), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("AUTH_003");
    }
}
