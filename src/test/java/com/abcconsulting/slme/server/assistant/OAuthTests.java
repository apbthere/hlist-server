package com.abcconsulting.slme.server.assistant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthTests {

    private static final String PASSWORD = "oauth-password";
    private static final String REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String VERIFIER = "a-long-random-code-verifier-0123456789-abcdefghijklmnop";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mcpPointsClientsToTheAuthorizationServer() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\""));
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(jsonPath("$.resource").value("http://localhost/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost"));
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(jsonPath("$.issuer").value("http://localhost"))
                .andExpect(jsonPath("$.registration_endpoint").value("http://localhost/oauth/register"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
    }

    @Test
    void aPublicClientConnectsWithPkce() throws Exception {
        String user = createUser();
        String clientId = register("{\"client_name\":\"ChatGPT\",\"redirect_uris\":[\"" + REDIRECT + "\"],"
                + "\"token_endpoint_auth_method\":\"none\"}", false);

        String code = approve(user, clientId);
        // A wrong verifier fails, and so does the code afterwards (codes work once).
        mockMvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("client_id", clientId)
                        .param("redirect_uri", REDIRECT).param("code_verifier", "wrong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        code = approve(user, clientId);
        String token = JsonPath.read(mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("client_id", clientId)
                        .param("redirect_uri", REDIRECT).param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString(), "$.access_token");
        mockMvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("client_id", clientId)
                        .param("code_verifier", VERIFIER))
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").isArray());

        // Connecting again replaces the client's token rather than adding one.
        code = approve(user, clientId);
        mockMvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("client_id", clientId)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/access-tokens").with(httpBasic(user, PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("ChatGPT"));
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aConfidentialClientAuthenticatesWithBasic() throws Exception {
        String user = createUser();
        String registered = mockMvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"Claude\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("client_secret_basic"))
                .andReturn().getResponse().getContentAsString();
        String clientId = JsonPath.read(registered, "$.client_id");
        String secret = JsonPath.read(registered, "$.client_secret");
        String code = code(mockMvc.perform(post("/api/oauth/requests/" + startAuthorization(clientId,
                        "https://claude.ai/api/mcp/auth_callback")).with(httpBasic(user, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andReturn().getResponse().getContentAsString());
        String basic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":wrong").getBytes());
        mockMvc.perform(post("/oauth/token").header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("code_verifier", VERIFIER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        basic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes());
        mockMvc.perform(post("/oauth/token").header("Authorization", basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code").param("code", code).param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", startsWith("hlist_")));
    }

    @Test
    void badRequestsAreRefused() throws Exception {
        register("{\"redirect_uris\":[\"http://evil.example/callback\"]}", true);
        String clientId = register("{\"redirect_uris\":[\"" + REDIRECT + "\"],\"token_endpoint_auth_method\":\"none\"}", false);
        // An unregistered redirect is never followed.
        mockMvc.perform(get("/oauth/authorize").param("client_id", clientId).param("response_type", "code")
                        .param("redirect_uri", "https://evil.example/callback").param("code_challenge", "x")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
        // Without PKCE, the client is told why.
        mockMvc.perform(get("/oauth/authorize").param("client_id", clientId).param("response_type", "code")
                        .param("redirect_uri", REDIRECT).param("state", "s1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith(REDIRECT + "?error=invalid_request")))
                .andExpect(header().string("Location", containsString("state=s1")));
        // Approving needs a signed-in user; declining sends access_denied.
        String requestId = startAuthorization(clientId, REDIRECT);
        mockMvc.perform(get("/api/oauth/requests/" + requestId)).andExpect(jsonPath("$.redirectHost").value("chatgpt.com"));
        mockMvc.perform(post("/api/oauth/requests/" + requestId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/oauth/requests/" + requestId).with(httpBasic(createUser(), PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}"))
                .andExpect(jsonPath("$.redirect", startsWith(REDIRECT + "?error=access_denied")));
        mockMvc.perform(get("/api/oauth/requests/" + requestId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/authorize.html")).andExpect(status().isOk());
    }

    @Test
    void redirectAddressesMustBeHttpsOrLocal() {
        assertTrue(OAuthService.allowedRedirect("https://chatgpt.com/connector_platform_oauth_redirect"));
        assertTrue(OAuthService.allowedRedirect("http://localhost:6274/oauth/callback"));
        assertFalse(OAuthService.allowedRedirect("http://example.com/callback"));
        assertFalse(OAuthService.allowedRedirect("https://example.com/callback#fragment"));
        assertFalse(OAuthService.allowedRedirect("javascript:alert(1)"));
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    private String register(String body, boolean refused) throws Exception {
        var result = mockMvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        if (refused) {
            result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
            return null;
        }
        return JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.client_id");
    }

    /** Starts an authorization like the client's browser would, returning the pending request's ID. */
    private String startAuthorization(String clientId, String redirect) throws Exception {
        String location = mockMvc.perform(get("/oauth/authorize").param("client_id", clientId)
                        .param("response_type", "code").param("redirect_uri", redirect).param("state", "xyz")
                        .param("code_challenge", OAuthService.challenge(VERIFIER)).param("code_challenge_method", "S256")
                        .param("scope", "lists").param("resource", "http://localhost/mcp"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("/authorize.html?request=")))
                .andReturn().getResponse().getHeader("Location");
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("request");
    }

    private String approve(String user, String clientId) throws Exception {
        String response = mockMvc.perform(post("/api/oauth/requests/" + startAuthorization(clientId, REDIRECT))
                        .with(httpBasic(user, PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirect", containsString("state=xyz")))
                .andReturn().getResponse().getContentAsString();
        return code(response);
    }

    private static String code(String response) {
        Map<String, java.util.List<String>> parameters = UriComponentsBuilder
                .fromUriString(JsonPath.read(response, "$.redirect")).build().getQueryParams();
        return parameters.get("code").getFirst();
    }

    private String createUser() throws Exception {
        String username = UUID.randomUUID().toString().substring(0, 30);
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        return username;
    }
}
