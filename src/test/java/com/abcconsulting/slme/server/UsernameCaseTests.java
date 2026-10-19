package com.abcconsulting.slme.server;

import com.abcconsulting.slme.server.service.UserAccountService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UsernameCaseTests {

	private static final String PASSWORD = "case-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void signInIgnoresCapitalizationAndUsesTheStoredName() throws Exception {
		String stored = "Alex-" + UUID.randomUUID();
		userAccountService.createUser(stored, PASSWORD);
		String typed = stored.toLowerCase(Locale.ROOT);

		MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
						.param("username", typed).param("password", PASSWORD).param("remember-me", "true"))
				.andExpect(status().isNoContent())
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mockMvc.perform(get("/api/users/me").session(session))
				.andExpect(jsonPath("$.username").value(stored));

		// Data created after a lowercase sign-in belongs to the stored name.
		mockMvc.perform(post("/api/shopping-lists").session(session).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Weekly\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(stored));

		// The 30-day cookie from that sign-in works on its own.
		Cookie rememberMe = Arrays.stream(login.getResponse().getCookies())
				.filter(cookie -> cookie.getName().equals("remember-me")).findFirst().orElseThrow();
		mockMvc.perform(get("/api/users/me").cookie(rememberMe))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(stored));

		// HTTP Basic (tools, tests) too.
		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(stored.toUpperCase(Locale.ROOT), PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].username").value(stored));

		mockMvc.perform(post("/api/auth/login").with(csrf()).param("username", typed).param("password", "wrong"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void signUpRefusesANameThatDiffersOnlyInCapitalization() throws Exception {
		String stored = "Sam-" + UUID.randomUUID();
		userAccountService.createUser(stored, PASSWORD);
		mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + stored.toLowerCase(Locale.ROOT) + "\",\"password\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Username already exists"));
	}

	@Test
	void accountsThatAlreadyDifferOnlyInCaseAreNotConfused() throws Exception {
		String base = "pat-" + UUID.randomUUID();
		String lower = base;
		String capitalized = "P" + base.substring(1);
		// Created before this rule existed: insert both directly.
		for (String name : new String[] {lower, capitalized}) {
			jdbcTemplate.update("INSERT INTO users (username, password, enabled) VALUES (?, ?, TRUE)",
					name, passwordEncoder.encode(PASSWORD));
		}
		mockMvc.perform(get("/api/users/me").with(httpBasic(lower, PASSWORD)))
				.andExpect(jsonPath("$.username").value(lower));
		mockMvc.perform(get("/api/users/me").with(httpBasic(capitalized, PASSWORD)))
				.andExpect(jsonPath("$.username").value(capitalized));
		// Any other capitalization is ambiguous, so it signs in to neither.
		mockMvc.perform(get("/api/users/me").with(httpBasic(base.toUpperCase(Locale.ROOT), PASSWORD)))
				.andExpect(status().isUnauthorized());
	}
}
