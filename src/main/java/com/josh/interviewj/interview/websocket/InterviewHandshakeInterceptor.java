package com.josh.interviewj.interview.websocket;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.service.TokenBlacklistService;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class InterviewHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = resolveToken(request);
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token) || tokenBlacklistService.isBlacklisted(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String username = jwtUtil.extractUsername(token);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        UUID interviewId = resolveInterviewId(request.getURI());
        if (interviewId == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        Optional<InterviewSession> sessionOptional = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId());
        if (sessionOptional.isEmpty()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        InterviewSession session = sessionOptional.get();
        attributes.put("username", username);
        attributes.put("userId", user.getId());
        attributes.put("interviewId", session.getExternalId());
        attributes.put("chatSessionId", session.getChatSessionId());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    private String resolveToken(ServerHttpRequest request) {
        String token = resolveQueryToken(request);
        if (token != null && !token.isBlank()) {
            return token;
        }
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null) {
            return null;
        }
        String lower = authorization.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    private String resolveQueryToken(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        return servletRequest.getServletRequest().getParameter("token");
    }

    private UUID resolveInterviewId(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] segments = path.split("/");
        String candidate = segments[segments.length - 1];
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
