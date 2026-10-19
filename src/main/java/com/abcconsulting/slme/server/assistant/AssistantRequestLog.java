package com.abcconsulting.slme.server.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs failed requests from AI assistants (/mcp, OAuth), which otherwise leave no trace: a 401 or 400 is all the
 * assistant sees, and it rarely says which. Runs before Spring Security, so refused sign-ins are logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AssistantRequestLog extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AssistantRequestLog.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(AccessTokenFilter.MCP_PATH) || path.startsWith("/oauth/")
                || path.startsWith("/.well-known/oauth") || path.equals("/.well-known/openid-configuration"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() >= 400) {
                String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                log.info("Assistant request {} {} -> {} ({}, {})", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), authorization == null ? "no token" : authorization.split(" ")[0],
                        request.getHeader(HttpHeaders.USER_AGENT));
            }
        }
    }
}
