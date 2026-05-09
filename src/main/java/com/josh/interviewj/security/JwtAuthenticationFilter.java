package com.josh.interviewj.security;

import com.josh.interviewj.common.exception.ErrorResponse;
import com.josh.interviewj.common.api.RequestIdContext;
import com.josh.interviewj.auth.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Request filter that authenticates Bearer JWT tokens and populates the security context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Performs JWT extraction, blacklist check, and SecurityContext setup.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String username;
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        jwt = authHeader.substring(7);
        
        try {
            if (tokenBlacklistService.isBlacklisted(jwt)) {
                log.warn("Token is blacklisted");
                writeUnauthorizedResponse(response, "Token has expired. Please log in again");
                return;
            }

            username = jwtUtil.extractUsername(jwt);
            
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
                
                if (jwtUtil.validateToken(jwt)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    writeUnauthorizedResponse(response, "JWT is invalid or expired");
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("JWT验证失败: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            writeUnauthorizedResponse(response, "JWT is invalid or expired");
            return;
        }
        
        filterChain.doFilter(request, response);
    }

    /**
     * Writes a consistent unauthorized response body when authentication fails.
     *
     * @param response HTTP response
     * @param message error message
     * @throws IOException when response writing fails
     */
    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .code(HttpServletResponse.SC_UNAUTHORIZED)
                .message(message)
                .error(ErrorResponse.ErrorDetails.builder()
                        .type("AUTH_001")
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();

        String responseBody = String.format(
                "{\"code\":%d,\"message\":\"%s\",\"error\":{\"type\":\"%s\"},\"timestamp\":\"%s\",\"requestId\":\"%s\"}",
                errorResponse.getCode(),
                errorResponse.getMessage(),
                errorResponse.getError().getType(),
                errorResponse.getTimestamp(),
                errorResponse.getRequestId()
        );
        response.getWriter().write(responseBody);
    }
}
