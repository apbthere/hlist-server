package com.abcconsulting.slme.server;

import com.abcconsulting.slme.server.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LoginThrottlingTests {

	private static final String PASSWORD = "throttle-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;

	@Test
	void fiveWrongPasswordsPauseSignInToTheAccount() throws Exception {
		String username = newUser();
		for (int attempt = 0; attempt < 5; attempt++) {
			signIn(username, "wrong", "203.0.113.1").andExpect(status().isUnauthorized());
		}

		// Even the right password, and from another address.
		signIn(username, PASSWORD, "203.0.113.2")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "900"))
				.andExpect(jsonPath("$.message").value(
						"Too many failed sign-ins. Try again in 15 minutes, or sign in with a passkey."));
		mockMvc.perform(get("/api/users/me").with(httpBasic(username, PASSWORD)).with(from("203.0.113.2")))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void signingInStartsTheCountOver() throws Exception {
		String username = newUser();
		for (int attempt = 0; attempt < 4; attempt++) {
			signIn(username, "wrong", "203.0.113.3").andExpect(status().isUnauthorized());
		}
		signIn(username, PASSWORD, "203.0.113.3").andExpect(status().isNoContent());

		for (int attempt = 0; attempt < 5; attempt++) {
			signIn(username, "wrong", "203.0.113.3").andExpect(status().isUnauthorized());
		}
		signIn(username, "wrong", "203.0.113.3").andExpect(status().isTooManyRequests());
	}

	@Test
	void basicSignInIsLimitedToo() throws Exception {
		String username = newUser();
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, "wrong")).with(from("203.0.113.4")))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, PASSWORD)).with(from("203.0.113.4")))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulBasicRequestsDontUseUpAttempts() throws Exception {
		String username = newUser();
		for (int attempt = 0; attempt < 30; attempt++) {
			mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, PASSWORD)).with(from("203.0.113.5")))
					.andExpect(status().isOk());
		}
	}

	@Test
	void oneAddressGuessingManyAccountsIsPaused() throws Exception {
		for (int attempt = 0; attempt < 20; attempt++) {
			signIn("nobody-" + UUID.randomUUID(), "wrong", "203.0.113.6").andExpect(status().isUnauthorized());
		}

		signIn(newUser(), PASSWORD, "203.0.113.6").andExpect(status().isTooManyRequests());
		signIn(newUser(), PASSWORD, "203.0.113.7").andExpect(status().isNoContent());
	}

	private String newUser() {
		String username = "throttle-" + UUID.randomUUID();
		userAccountService.createUser(username, PASSWORD);
		return username;
	}

	private ResultActions signIn(String username, String password, String address) throws Exception {
		return mockMvc.perform(post("/api/auth/login").with(csrf()).with(from(address))
				.param("username", username).param("password", password));
	}

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
