package com.josh.interviewj.security;

import com.josh.interviewj.auth.service.TokenBlacklistService;
import com.josh.interviewj.common.api.RequestIdContext;
import com.josh.interviewj.common.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private RequestIdFilter requestIdFilter;
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtUtil, userDetailsService, tokenBlacklistService);
        requestIdFilter = new RequestIdFilter();
    }

    @AfterEach
    void tearDown() {
        RequestIdContext.clear();
    }

    @Test
    void unauthorizedResponse_ReusesIncomingRequestIdFromCurrentContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER_NAME, "req_auth_001");
        request.addHeader("Authorization", "Bearer token_value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenBlacklistService.isBlacklisted("token_value")).thenReturn(true);

        requestIdFilter.doFilter(request, response, invokeJwtFilterChain(response));

        String body = response.getContentAsString();
        assertThat(response.getStatus()).isEqualTo(MockHttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo("req_auth_001");
        assertThat(body).contains("\"requestId\":\"req_auth_001\"");
    }

    @Test
    void unauthorizedResponse_GeneratesStableRequestIdWhenMissingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token_value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenBlacklistService.isBlacklisted("token_value")).thenReturn(true);

        requestIdFilter.doFilter(request, response, invokeJwtFilterChain(response));

        String responseRequestId = response.getHeader(RequestIdContext.HEADER_NAME);
        String body = response.getContentAsString();
        assertThat(response.getStatus()).isEqualTo(MockHttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseRequestId).startsWith("req_");
        assertThat(body).contains("\"requestId\":\"" + responseRequestId + "\"");
    }

    private FilterChain invokeJwtFilterChain(MockHttpServletResponse response) {
        return (req, res) -> jwtAuthenticationFilter.doFilter(
                (MockHttpServletRequest) req,
                response,
                (innerReq, innerRes) -> {
                }
        );
    }
}
