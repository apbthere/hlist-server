package com.abcconsulting.slme.server.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Signs in requests to /mcp that carry {@code Authorization: Bearer hlist_...}, or the bare token (some assistants,
 * such as Grok's API, take an "authorization" value without saying whether they add "Bearer "; the hlist_ prefix
 * marks the token either way). Tokens work nowhere else, so a
 * leaked token can't change the password, manage passkeys or create more tokens. Nothing is kept in a session;
 * every request carries the token.
 */
public class AccessTokenFilter extends OncePerRequestFilter {

    public static final String MCP_PATH = "/mcp";
    private static final String BEARER = "Bearer ";

    private final AccessTokenService tokens;
    private final UserDetailsService users;

    public AccessTokenFilter(AccessTokenService tokens, UserDetailsService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token(request) == null
                || !MCP_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    /** The token in the Authorization header: "Bearer hlist_..." or just "hlist_...". */
    private static String token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        header = header.strip();
        if (header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return header.substring(BEARER.length()).strip();
        }
        return header.startsWith(AccessTokenService.PREFIX) ? header : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = token(request);
        Optional<UserDetails> user = tokens.authenticate(token).flatMap(this::enabledUser);
        if (user.isPresent()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new TokenAuthentication(user.get()));
            SecurityContextHolder.setContext(context);
        }
        // An unknown token leaves the request signed out, so it gets the usual 401.
        chain.doFilter(request, response);
    }

    /** A user signed in by an access token; /mcp accepts only these, so it needs no CSRF protection. */
    public static class TokenAuthentication extends UsernamePasswordAuthenticationToken {

        TokenAuthentication(UserDetails user) {
            super(user, null, user.getAuthorities());
        }
    }

    private Optional<UserDetails> enabledUser(String username) {
        try {
            return Optional.of(users.loadUserByUsername(username)).filter(UserDetails::isEnabled);
        } catch (UsernameNotFoundException removed) {
            return Optional.empty();
        }
    }
}
