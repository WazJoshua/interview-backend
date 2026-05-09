package com.josh.interviewj.common.api;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        RequestIdContext.clear();
    }

    @Test
    void doFilterInternal_ReusesIncomingRequestIdAndClearsContextAfterChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER_NAME, "req_fixed_001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> requestIdInChain.set(RequestIdContext.get()));

        assertThat(requestIdInChain.get()).isEqualTo("req_fixed_001");
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo("req_fixed_001");
        assertThat(request.getAttribute(RequestIdContext.ATTRIBUTE_NAME)).isEqualTo("req_fixed_001");
        assertThat(RequestIdContext.get()).isNull();
    }

    @Test
    void doFilterInternal_GeneratesRequestIdWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> requestIdInChain.set(RequestIdContext.get()));

        assertThat(requestIdInChain.get()).startsWith("req_");
        assertThat(response.getHeader(RequestIdContext.HEADER_NAME)).isEqualTo(requestIdInChain.get());
        assertThat(request.getAttribute(RequestIdContext.ATTRIBUTE_NAME)).isEqualTo(requestIdInChain.get());
        assertThat(RequestIdContext.get()).isNull();
    }
}
