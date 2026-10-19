package com.abcconsulting.slme.server.assistant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth for AI assistants, as the MCP authorization spec describes: metadata (RFC 9728 and RFC 8414), dynamic
 * client registration (RFC 7591), the authorization endpoint (which sends the user to authorize.html to sign in
 * and approve), and the token endpoint (authorization code with PKCE). See {@link OAuthService}.
 */
@RestController
public class OAuthController {

    private final OAuthService oauth;

    public OAuthController(OAuthService oauth) {
        this.oauth = oauth;
    }

    /** This server's address as the client sees it (Caddy's X-Forwarded headers are trusted in production). */
    static String base() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().replacePath("").build().toUriString();
    }

    @CrossOrigin
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> protectedResource() {
        return Map.of(
                "resource", base() + AccessTokenFilter.MCP_PATH,
                "authorization_servers", List.of(base()),
                "scopes_supported", List.of(OAuthService.SCOPE),
                "bearer_methods_supported", List.of("header"),
                "resource_name", "HList shopping lists");
    }

    @CrossOrigin
    @GetMapping({"/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"})
    public Map<String, Object> authorizationServer() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", base());
        metadata.put("authorization_endpoint", base() + "/oauth/authorize");
        metadata.put("token_endpoint", base() + "/oauth/token");
        metadata.put("registration_endpoint", base() + "/oauth/register");
        metadata.put("scopes_supported", List.of(OAuthService.SCOPE));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post", "client_secret_basic"));
        return metadata;
    }

    @CrossOrigin
    @PostMapping(path = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode request) {
        List<String> redirectUris = new ArrayList<>();
        request.path("redirect_uris").forEach(uri -> redirectUris.add(uri.asString()));
        // RFC 7591: without token_endpoint_auth_method, the client authenticates with a secret (Basic).
        String authMethod = request.path("token_endpoint_auth_method").asString("client_secret_basic");
        if (!List.of("none", "client_secret_post", "client_secret_basic").contains(authMethod)) {
            throw new OAuthService.OAuthException("invalid_client_metadata", "Unsupported token_endpoint_auth_method " + authMethod);
        }
        OAuthService.Registered registered = oauth.register(request.path("client_name").asString(null), redirectUris,
                !"none".equals(authMethod));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", registered.client().clientId());
        response.put("client_name", registered.client().clientName());
        response.put("redirect_uris", registered.client().redirectUris());
        response.put("grant_types", List.of("authorization_code"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", authMethod);
        response.put("scope", OAuthService.SCOPE);
        response.put("client_id_issued_at", System.currentTimeMillis() / 1000);
        if (registered.secret() != null) {
            response.put("client_secret", registered.secret());
            response.put("client_secret_expires_at", 0);
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
    }

    /** Checks the request and sends the browser to authorize.html, where the user signs in and approves. */
    @GetMapping("/oauth/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod) {
        try {
            String requestId = oauth.startAuthorization(clientId, redirectUri, responseType, state, codeChallenge,
                    codeChallengeMethod);
            return redirect(UriComponentsBuilder.fromPath("/authorize.html").queryParam("request", requestId)
                    .build().toUriString());
        } catch (OAuthService.OAuthException error) {
            return redirect(oauth.errorRedirect(clientId, redirectUri, state, error));
        } catch (IllegalArgumentException refused) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                    .body("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                            + "<title>HList</title><p style=\"font:16px -apple-system,sans-serif;margin:40px\">"
                            + HtmlUtils.htmlEscape(refused.getMessage()) + "</p>");
        }
    }

    /** What authorize.html shows: which app is asking. Not secret, so it needs no sign-in. */
    @GetMapping("/api/oauth/requests/{requestId}")
    public ResponseEntity<Map<String, String>> pending(@PathVariable String requestId) {
        return oauth.pending(requestId)
                .map(pending -> ResponseEntity.ok(Map.of(
                        "clientName", pending.client().clientName(),
                        "redirectHost", URI.create(pending.redirectUri()).getHost())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message",
                        "This sign-in has expired. Start connecting again from the app.")));
    }

    /** The signed-in user's answer; the page then sends the browser back to the app. */
    @PostMapping("/api/oauth/requests/{requestId}")
    public Map<String, String> decide(Authentication authentication, @PathVariable String requestId,
            @RequestBody Decision decision) {
        return Map.of("redirect", oauth.decide(requestId, authentication.getName(), decision.approve()));
    }

    public record Decision(boolean approve) {
    }

    @CrossOrigin
    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(required = false) String code,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "code_verifier", required = false) String codeVerifier,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret) {
        if (!"authorization_code".equals(grantType)) {
            throw new OAuthService.OAuthException("unsupported_grant_type", "Only authorization_code is supported.");
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String[] credentials = new String(Base64.getDecoder().decode(authorization.substring(6).strip()),
                    StandardCharsets.UTF_8).split(":", 2);
            clientId = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
            clientSecret = credentials.length > 1 ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8) : null;
        }
        AccessTokenService.CreatedToken token = oauth.exchange(code, clientId, clientSecret, redirectUri, codeVerifier);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "access_token", token.token(),
                "token_type", "Bearer",
                "scope", OAuthService.SCOPE));
    }

    @ExceptionHandler(OAuthService.OAuthException.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthService.OAuthException error, HttpServletRequest request) {
        HttpStatus status = "invalid_client".equals(error.error()) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", error.error(), "error_description", error.getMessage()));
    }

    private static ResponseEntity<String> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
}
