package com.josh.interviewj.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ensures each HTTP request has one stable request id in thread-local context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = RequestIdContext.normalizeIncoming(request.getHeader(RequestIdContext.HEADER_NAME));
        if (incoming == null) {
            incoming = RequestIdContext.normalizeIncoming(request.getHeader(RequestIdContext.ALTERNATE_HEADER_NAME));
        }

        String requestId = incoming != null ? incoming : RequestIdContext.getOrCreate();
        RequestIdContext.set(requestId);
        request.setAttribute(RequestIdContext.ATTRIBUTE_NAME, requestId);
        response.setHeader(RequestIdContext.HEADER_NAME, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
        }
    }
}
