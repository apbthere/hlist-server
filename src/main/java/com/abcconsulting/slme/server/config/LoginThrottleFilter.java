package com.abcconsulting.slme.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Applies {@link LoginThrottle} to everything that checks a password: the sign-in form and HTTP Basic. Answers 429
 * with Retry-After and a {message} once the attempts are used up. The client address is the real one behind Caddy
 * (server.forward-headers-strategy=native).
 */
class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;
    private final String loginPath;
    private final BasicAuthenticationConverter basicConverter = new BasicAuthenticationConverter();

    LoginThrottleFilter(LoginThrottle throttle, String loginPath) {
        this.throttle = throttle;
        this.loginPath = loginPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = passwordUsername(request);
        if (username == null) {
            chain.doFilter(request, response);
            return;
        }
        String address = request.getRemoteAddr();
        Optional<Duration> wait = throttle.tryAttempt(address, username);
        if (wait.isPresent()) {
            tooManyAttempts(response, wait.get());
            return;
        }
        chain.doFilter(request, response);
        Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
        if (signedIn != null && signedIn.isAuthenticated() && !(signedIn instanceof AnonymousAuthenticationToken)
                && signedIn.getName().equalsIgnoreCase(username.strip())) {
            throttle.succeeded(address, username);
        }
    }

    /** The username of a password sign-in ("" when none was given), or null when the request doesn't have one. */
    private String passwordUsername(HttpServletRequest request) {
        if (HttpMethod.POST.matches(request.getMethod())
                && loginPath.equals(request.getRequestURI().substring(request.getContextPath().length()))) {
            String username = request.getParameter("username");
            return username == null ? "" : username;
        }
        try {
            UsernamePasswordAuthenticationToken basic = basicConverter.convert(request);
            return basic == null ? null : basic.getName();
        } catch (AuthenticationException malformed) {
            // BasicAuthenticationFilter rejects it without checking a password.
            return null;
        }
    }

    private static void tooManyAttempts(HttpServletResponse response, Duration wait) throws IOException {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        long minutes = (seconds + 59) / 60;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Too many failed sign-ins. Try again in " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ", or sign in with a passkey.\"}");
    }
}
