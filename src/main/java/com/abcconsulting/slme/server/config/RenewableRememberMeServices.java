package com.abcconsulting.slme.server.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent remember-me that can revoke every stored login for a user, e.g. after a password change,
 * while keeping the device that made the change signed in.
 */
public class RenewableRememberMeServices extends PersistentTokenBasedRememberMeServices {

    /** How long a just-replaced token stays valid for requests that were already on their way. */
    static final Duration REPLACED_TOKEN_GRACE = Duration.ofSeconds(60);

    private final PersistentTokenRepository tokenRepository;
    private final Map<String, ReplacedToken> replacedTokens = new ConcurrentHashMap<>();
    private final Object[] seriesLocks = new Object[64];

    public RenewableRememberMeServices(
            String key,
            UserDetailsService userDetailsService,
            PersistentTokenRepository tokenRepository) {
        super(key, userDetailsService, tokenRepository);
        this.tokenRepository = tokenRepository;
        for (int i = 0; i < seriesLocks.length; i++) {
            seriesLocks[i] = new Object();
        }
    }

    /**
     * Same as the standard persistent remember-me check (series lookup, theft detection, expiry, token
     * rotation), made safe for pages that send several requests at once when their session has expired.
     * The standard version rotates the token on the first request and treats the others, which still carry
     * the previous token, as cookie theft, deleting every remember-me login of the user (signing out all
     * their devices). Here requests for the same series are handled one at a time, and the token replaced
     * within the last {@link #REPLACED_TOKEN_GRACE} is accepted without rotating again; the response
     * carries the current token so the client catches up. Anything older is still treated as theft.
     */
    @Override
    protected UserDetails processAutoLoginCookie(
            String[] cookieTokens, HttpServletRequest request, HttpServletResponse response) {
        if (cookieTokens.length != 2) {
            throw new InvalidCookieException("Cookie token did not contain 2 tokens, but contained '"
                    + Arrays.asList(cookieTokens) + "'");
        }
        String series = cookieTokens[0];
        String presentedToken = cookieTokens[1];
        synchronized (lockFor(series)) {
            PersistentRememberMeToken stored = tokenRepository.getTokenForSeries(series);
            if (stored == null) {
                throw new RememberMeAuthenticationException("No persistent token found for series id: " + series);
            }
            if (!matches(stored.getTokenValue(), presentedToken)) {
                ReplacedToken replaced = replacedTokens.get(series);
                if (replaced == null || !replaced.acceptable(presentedToken)) {
                    tokenRepository.removeUserTokens(stored.getUsername());
                    replacedTokens.remove(series);
                    throw new CookieTheftException(messages.getMessage(
                            "PersistentTokenBasedRememberMeServices.cookieStolen",
                            "Invalid remember-me token (Series/token) mismatch. Implies previous cookie theft attack."));
                }
                // A request that raced the rotation: accept it and hand out the current token.
                setCookie(new String[] {series, stored.getTokenValue()}, getTokenValiditySeconds(), request, response);
                return getUserDetailsService().loadUserByUsername(stored.getUsername());
            }
            if (stored.getDate().getTime() + getTokenValiditySeconds() * 1000L < System.currentTimeMillis()) {
                throw new RememberMeAuthenticationException("Remember-me login has expired");
            }
            PersistentRememberMeToken rotated = new PersistentRememberMeToken(
                    stored.getUsername(), series, generateTokenData(), new Date());
            tokenRepository.updateToken(series, rotated.getTokenValue(), rotated.getDate());
            replacedTokens.put(series, new ReplacedToken(presentedToken, Instant.now()));
            setCookie(new String[] {series, rotated.getTokenValue()}, getTokenValiditySeconds(), request, response);
            return getUserDetailsService().loadUserByUsername(stored.getUsername());
        }
    }

    private Object lockFor(String series) {
        return seriesLocks[Math.floorMod(series.hashCode(), seriesLocks.length)];
    }

    private static boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private record ReplacedToken(String tokenValue, Instant replacedAt) {

        boolean acceptable(String presentedToken) {
            return matches(tokenValue, presentedToken)
                    && Instant.now().isBefore(replacedAt.plus(REPLACED_TOKEN_GRACE));
        }
    }

    public void revokeOtherDevices(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        tokenRepository.removeUserTokens(authentication.getName());
        if (extractRememberMeCookie(request) != null) {
            onLoginSuccess(request, response, authentication);
        }
    }

    /**
     * Logout runs before remember-me authentication, so once the HTTP session has expired there is no
     * authentication here. Resolve the user from a valid cookie instead, so the stored token is still revoked.
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) {
            String username = usernameFromCookie(request);
            if (username != null) {
                tokenRepository.removeUserTokens(username);
            }
        }
        super.logout(request, response, authentication);
    }

    private String usernameFromCookie(HttpServletRequest request) {
        String cookieValue = extractRememberMeCookie(request);
        if (cookieValue == null || cookieValue.isEmpty()) {
            return null;
        }
        try {
            String[] cookieTokens = decodeCookie(cookieValue);
            if (cookieTokens.length != 2) {
                return null;
            }
            PersistentRememberMeToken storedToken = tokenRepository.getTokenForSeries(cookieTokens[0]);
            if (storedToken == null || !matches(storedToken.getTokenValue(), cookieTokens[1])) {
                return null;
            }
            return storedToken.getUsername();
        } catch (InvalidCookieException exception) {
            return null;
        }
    }
}
