package com.abcconsulting.slme.server;

import com.abcconsulting.slme.server.service.UserAccountService;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adds a passkey and signs in with it, using webauthn4j's software authenticator in place of Face ID, with the
 * JSON a browser sends.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasskeyTests {

	private static final String PASSWORD = "passkey-password";
	private static final String ORIGIN = "http://localhost:8080";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder FROM_BASE64URL = Base64.getUrlDecoder();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;

	/** The session ID before the latest passkey sign-in. */
	private String lastSignInSessionId;

	@Test
	void addsAPasskeyAndSignsInWithIt() throws Exception {
		String username = "Passkey-" + UUID.randomUUID();
		userAccountService.createUser(username, PASSWORD);
		ClientPlatform device = new ClientPlatform(new Origin(ORIGIN),
				new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));

		String passkeyId = addPasskey(signIn(username), device, "iPad");

		MockHttpSession owner = signIn(username);
		mockMvc.perform(get("/api/users/me/passkeys").session(owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(passkeyId))
				.andExpect(jsonPath("$[0].label").value("iPad"))
				.andExpect(jsonPath("$[0].created").isNotEmpty());

		// Signing in with it starts a session and, when asked, issues the 30-day cookie.
		MvcResult login = signInWithPasskey(device, true);
		assertThat(login.getResponse().getStatus()).isEqualTo(204);
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mockMvc.perform(get("/api/users/me").session(session))
				.andExpect(jsonPath("$.username").value(username));
		// A new session ID, like a password sign-in (no session fixation).
		assertThat(session.getId()).isNotEqualTo(lastSignInSessionId);
		Cookie rememberMe = Arrays.stream(login.getResponse().getCookies())
				.filter(cookie -> cookie.getName().equals("remember-me")).findFirst().orElseThrow();
		mockMvc.perform(get("/api/users/me").cookie(rememberMe))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username));
		mockMvc.perform(get("/api/users/me/passkeys").session(owner))
				.andExpect(jsonPath("$[0].lastUsed").isNotEmpty());

		// Without "Keep Me Signed In", no cookie.
		MvcResult withoutCookie = signInWithPasskey(device, false);
		assertThat(withoutCookie.getResponse().getStatus()).isEqualTo(204);
		assertThat(withoutCookie.getResponse().getCookie("remember-me")).isNull();

		// Someone else can't remove it.
		String other = "Other-" + UUID.randomUUID();
		userAccountService.createUser(other, PASSWORD);
		mockMvc.perform(delete("/api/users/me/passkeys/" + passkeyId).session(signIn(other)).with(csrf()))
				.andExpect(status().isNotFound());

		// Once removed, it no longer signs in.
		mockMvc.perform(delete("/api/users/me/passkeys/" + passkeyId).session(owner).with(csrf()))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/users/me/passkeys").session(owner))
				.andExpect(jsonPath("$.length()").value(0));
		MvcResult removed = signInWithPasskey(device, true);
		assertThat(removed.getResponse().getStatus()).isEqualTo(401);
		assertThat(removed.getResponse().getContentAsString()).contains("sign in with your password");
	}

	@Test
	void requiresFaceIdOrPasscodeAndASignedInUserToAddOne() throws Exception {
		JsonNode options = JSON.readTree(mockMvc.perform(post("/webauthn/register/options")
						.session(signIn(newUser())).with(csrf()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(options.at("/rp/id").asString()).isEqualTo("localhost");
		assertThat(options.at("/authenticatorSelection/userVerification").asString()).isEqualTo("required");
		assertThat(options.at("/authenticatorSelection/residentKey").asString()).isEqualTo("required");

		mockMvc.perform(post("/webauthn/register/options").with(csrf()))
				.andExpect(status().is4xxClientError());
		mockMvc.perform(post("/webauthn/register").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tellsSignInPagesWherePasskeysWork() throws Exception {
		mockMvc.perform(get("/api/auth/passkeys"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.origins[0]").value("http://localhost:5173"))
				.andExpect(jsonPath("$.origins[1]").value(ORIGIN));
	}

	private String newUser() {
		String username = "Passkey-" + UUID.randomUUID();
		userAccountService.createUser(username, PASSWORD);
		return username;
	}

	private MockHttpSession signIn(String username) throws Exception {
		MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("username", username).param("password", PASSWORD))
				.andExpect(status().isNoContent())
				.andReturn();
		return (MockHttpSession) login.getRequest().getSession(false);
	}

	/** What the client does after "Add Passkey": options, navigator.credentials.create(), then save it. */
	private String addPasskey(MockHttpSession session, ClientPlatform device, String label) throws Exception {
		JsonNode options = JSON.readTree(mockMvc.perform(post("/webauthn/register/options").session(session).with(csrf()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());

		PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential = device.create(
				new PublicKeyCredentialCreationOptions(
						new PublicKeyCredentialRpEntity(options.at("/rp/id").asString(), options.at("/rp/name").asString()),
						new PublicKeyCredentialUserEntity(
								bytes(options.at("/user/id")),
								options.at("/user/name").asString(),
								options.at("/user/displayName").asString()),
						new DefaultChallenge(bytes(options.at("/challenge"))),
						List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
						null,
						List.of(),
						new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
						AttestationConveyancePreference.NONE,
						null));

		ObjectNode body = JSON.createObjectNode();
		ObjectNode publicKey = body.putObject("publicKey");
		ObjectNode json = publicKey.putObject("credential");
		json.put("id", credential.getId());
		json.put("rawId", BASE64URL.encodeToString(credential.getRawId()));
		json.put("type", "public-key");
		json.put("authenticatorAttachment", "platform");
		json.putObject("clientExtensionResults");
		ObjectNode response = json.putObject("response");
		response.put("attestationObject", BASE64URL.encodeToString(credential.getResponse().getAttestationObject()));
		response.put("clientDataJSON", BASE64URL.encodeToString(credential.getResponse().getClientDataJSON()));
		response.putArray("transports").add("internal").add("hybrid");
		publicKey.put("label", label);

		mockMvc.perform(post("/webauthn/register").session(session).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
		return BASE64URL.encodeToString(credential.getRawId());
	}

	/** What the sign-in page does: options, navigator.credentials.get() (Face ID), then sign in. */
	private MvcResult signInWithPasskey(ClientPlatform device, boolean rememberMe) throws Exception {
		MvcResult optionsResult = mockMvc.perform(post("/webauthn/authenticate/options").with(csrf()))
				.andExpect(status().isOk())
				.andReturn();
		MockHttpSession session = (MockHttpSession) optionsResult.getRequest().getSession(false);
		lastSignInSessionId = session.getId();
		JsonNode options = JSON.readTree(optionsResult.getResponse().getContentAsString());
		assertThat(options.at("/userVerification").asString()).isEqualTo("required");

		PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential = device.get(
				new PublicKeyCredentialRequestOptions(
						new DefaultChallenge(bytes(options.at("/challenge"))),
						null,
						options.at("/rpId").asString(),
						null,
						UserVerificationRequirement.REQUIRED,
						null));

		ObjectNode json = JSON.createObjectNode();
		json.put("id", credential.getId());
		json.put("rawId", BASE64URL.encodeToString(credential.getRawId()));
		json.put("type", "public-key");
		json.put("authenticatorAttachment", "platform");
		json.putObject("clientExtensionResults");
		ObjectNode response = json.putObject("response");
		response.put("authenticatorData", BASE64URL.encodeToString(credential.getResponse().getAuthenticatorData()));
		response.put("clientDataJSON", BASE64URL.encodeToString(credential.getResponse().getClientDataJSON()));
		response.put("signature", BASE64URL.encodeToString(credential.getResponse().getSignature()));
		response.put("userHandle", BASE64URL.encodeToString(credential.getResponse().getUserHandle()));

		return mockMvc.perform(post("/login/webauthn" + (rememberMe ? "?remember-me=true" : ""))
						.session(session).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(json)))
				.andReturn();
	}

	private static byte[] bytes(JsonNode base64url) {
		return FROM_BASE64URL.decode(base64url.asString());
	}
}
