package com.abcconsulting.slme.server.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small OAuth 2.1 authorization server for AI assistants (ChatGPT, claude.ai): clients register themselves
 * (RFC 7591), the user approves them on authorize.html, and the client swaps the one-time code (with PKCE) for an
 * {@link AccessTokenService} token. Pending approvals and codes are kept in memory; a restart just means
 * connecting again.
 */
@Service
public class OAuthService {

    static final String SCOPE = "lists";
    private static final Duration REQUEST_LIFETIME = Duration.ofMinutes(15);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_PENDING = 1000;
    private static final int MAX_CLIENTS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final AccessTokenService tokens;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Pending> requests = new ConcurrentHashMap<>();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();

    public OAuthService(JdbcTemplate jdbcTemplate, AccessTokenService tokens) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokens = tokens;
    }

    public record Client(String clientId, String clientName, List<String> redirectUris, String secretHash) {

        boolean isPublic() {
            return secretHash == null;
        }
    }

    /** A registered client, and its secret when it asked for one (shown only now). */
    public record Registered(Client client, String secret) {
    }

    /** An authorization request waiting for the user to approve it. */
    public record Pending(Client client, String redirectUri, String state, String codeChallenge, Instant expires) {
    }

    private record Code(String clientId, String redirectUri, String codeChallenge, String username, Instant expires) {
    }

    /** An OAuth error: the code (RFC 6749 section 5.2) and a description for the person setting up the client. */
    public static class OAuthException extends RuntimeException {

        private final String error;

        OAuthException(String error, String description) {
            super(description);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    // ---- Registration ----

    public Registered register(String clientName, List<String> redirectUris, boolean confidential) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uris is required.");
        }
        for (String uri : redirectUris) {
            if (!allowedRedirect(uri)) {
                throw new OAuthException("invalid_redirect_uri",
                        "Redirect URIs must use HTTPS (or http://localhost) and have no fragment: " + uri);
            }
        }
        String name = clientName == null || clientName.isBlank() ? "AI assistant" : clientName.strip();
        if (name.length() > 100) {
            name = name.substring(0, 100).strip();
        }
        String joined = String.join("\n", redirectUris);
        if (joined.length() > 4000) {
            throw new OAuthException("invalid_client_metadata", "Too many redirect URIs.");
        }
        removeUnusedClients();
        String clientId = "hlist-" + AccessTokenService.newSecret().substring(0, 24);
        String secret = confidential ? AccessTokenService.newSecret() : null;
        jdbcTemplate.update("INSERT INTO oauth_client (client_id, client_name, redirect_uris, secret_hash) VALUES (?, ?, ?, ?)",
                clientId, name, joined, secret == null ? null : AccessTokenService.hash(secret));
        return new Registered(new Client(clientId, name, List.copyOf(redirectUris),
                secret == null ? null : AccessTokenService.hash(secret)), secret);
    }

    /** Anyone can register, so old registrations that never got a token make way for new ones. */
    private void removeUnusedClients() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth_client", Integer.class);
        if (count != null && count >= MAX_CLIENTS) {
            jdbcTemplate.update("""
                    DELETE FROM oauth_client c
                    WHERE c.created_at < DATEADD('DAY', -1, CURRENT_TIMESTAMP)
                        AND NOT EXISTS (SELECT 1 FROM access_token t WHERE t.client_id = c.client_id)
                    """);
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth_client", Integer.class);
            if (count != null && count >= MAX_CLIENTS) {
                throw new OAuthException("temporarily_unavailable", "Too many registered clients. Try again tomorrow.");
            }
        }
    }

    static boolean allowedRedirect(String uri) {
        try {
            URI parsed = new URI(uri);
            if (parsed.getFragment() != null || parsed.getHost() == null) {
                return false;
            }
            return "https".equalsIgnoreCase(parsed.getScheme())
                    || ("http".equalsIgnoreCase(parsed.getScheme())
                            && List.of("localhost", "127.0.0.1", "[::1]").contains(parsed.getHost().toLowerCase()));
        } catch (Exception invalid) {
            return false;
        }
    }

    public Optional<Client> client(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("SELECT client_id, client_name, redirect_uris, secret_hash FROM oauth_client WHERE client_id = ?",
                (row, number) -> new Client(row.getString("client_id"), row.getString("client_name"),
                        List.of(row.getString("redirect_uris").split("\n")), row.getString("secret_hash")),
                clientId).stream().findFirst();
    }

    // ---- Authorization ----

    /**
     * Checks an authorization request and keeps it for the user's approval. A bad client or redirect URI is an
     * IllegalArgumentException (shown to the user, never redirected to an unchecked address); other problems are
     * OAuthExceptions to send back to the client.
     */
    public String startAuthorization(String clientId, String redirectUri, String responseType, String state,
            String codeChallenge, String codeChallengeMethod) {
        Client client = client(clientId)
                .orElseThrow(() -> new IllegalArgumentException("This app isn't registered with HList. Connect it again."));
        String redirect = redirectUri != null ? redirectUri
                : client.redirectUris().size() == 1 ? client.redirectUris().getFirst() : null;
        if (redirect == null || !client.redirectUris().contains(redirect)) {
            throw new IllegalArgumentException("This app asked HList to send you to an address it didn't register.");
        }
        if (!"code".equals(responseType)) {
            throw new OAuthException("unsupported_response_type", "Only response_type=code is supported.");
        }
        if (codeChallenge == null || codeChallenge.isBlank() || !"S256".equals(codeChallengeMethod)) {
            throw new OAuthException("invalid_request", "PKCE with code_challenge_method=S256 is required.");
        }
        prune();
        if (requests.size() >= MAX_PENDING) {
            throw new OAuthException("temporarily_unavailable", "Too many sign-ins in progress. Try again shortly.");
        }
        String id = AccessTokenService.newSecret();
        requests.put(id, new Pending(client, redirect, state, codeChallenge, clock.instant().plus(REQUEST_LIFETIME)));
        return id;
    }

    public Optional<Pending> pending(String requestId) {
        Pending pending = requestId == null ? null : requests.get(requestId);
        return pending == null || pending.expires().isBefore(clock.instant()) ? Optional.empty() : Optional.of(pending);
    }

    /** The user's answer: where to send the browser, with a code when approved. */
    public String decide(String requestId, String username, boolean approved) {
        Pending pending = pending(requestId)
                .orElseThrow(() -> new IllegalArgumentException("This sign-in has expired. Start connecting again from the app."));
        requests.remove(requestId);
        if (!approved) {
            return redirect(pending, Map.of("error", "access_denied"));
        }
        String code = AccessTokenService.newSecret();
        codes.put(code, new Code(pending.client().clientId(), pending.redirectUri(), pending.codeChallenge(), username,
                clock.instant().plus(CODE_LIFETIME)));
        return redirect(pending, Map.of("code", code));
    }

    /** Where to send the browser with an error the client should see (e.g. no PKCE). */
    public String errorRedirect(String clientId, String redirectUri, String state, OAuthException error) {
        Client client = client(clientId).orElseThrow();
        String redirect = redirectUri != null ? redirectUri : client.redirectUris().getFirst();
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("error", error.error());
        parameters.put("error_description", error.getMessage());
        return redirect(new Pending(client, redirect, state, null, clock.instant()), parameters);
    }

    private String redirect(Pending pending, Map<String, String> parameters) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(pending.redirectUri());
        parameters.forEach(uri::queryParam);
        if (pending.state() != null) {
            uri.queryParam("state", pending.state());
        }
        return uri.encode().build().toUriString();
    }

    // ---- Token ----

    /** Swaps a code for an access token. clientSecret is null for public clients. */
    public AccessTokenService.CreatedToken exchange(String code, String clientId, String clientSecret,
            String redirectUri, String codeVerifier) {
        Client client = client(clientId).orElseThrow(() -> new OAuthException("invalid_client", "Unknown client."));
        if (!client.isPublic() && (clientSecret == null
                || !MessageDigest.isEqual(AccessTokenService.hash(clientSecret).getBytes(StandardCharsets.UTF_8),
                        client.secretHash().getBytes(StandardCharsets.UTF_8)))) {
            throw new OAuthException("invalid_client", "Wrong client secret.");
        }
        Code granted = code == null ? null : codes.remove(code);
        if (granted == null || granted.expires().isBefore(clock.instant()) || !granted.clientId().equals(clientId)) {
            throw new OAuthException("invalid_grant", "The code is unknown, used or expired.");
        }
        if (redirectUri != null && !redirectUri.equals(granted.redirectUri())) {
            throw new OAuthException("invalid_grant", "redirect_uri doesn't match the authorization request.");
        }
        if (codeVerifier == null || !challenge(codeVerifier).equals(granted.codeChallenge())) {
            throw new OAuthException("invalid_grant", "code_verifier doesn't match the code_challenge.");
        }
        try {
            return tokens.createForClient(granted.username(), client.clientName(), clientId);
        } catch (IllegalArgumentException tooMany) {
            throw new OAuthException("invalid_grant", tooMany.getMessage());
        }
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void prune() {
        Instant now = clock.instant();
        requests.values().removeIf(pending -> pending.expires().isBefore(now));
        codes.values().removeIf(code -> code.expires().isBefore(now));
    }
}
