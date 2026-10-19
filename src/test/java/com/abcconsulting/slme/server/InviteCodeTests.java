package com.abcconsulting.slme.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hlist.invite-code=family-secret")
@AutoConfigureMockMvc
class InviteCodeTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void registrationRequiresTheConfiguredInviteCode() throws Exception {
		mockMvc.perform(get("/api/auth/registration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inviteRequired").value(true));

		String username = UUID.randomUUID().toString();
		mockMvc.perform(register(username, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("An invite code is required to create an account"));
		mockMvc.perform(register(username, "wrong"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invite code is incorrect"));
		mockMvc.perform(register(username, " family-secret "))
				.andExpect(status().isCreated());
	}

	private static org.springframework.test.web.servlet.RequestBuilder register(String username, String inviteCode) {
		String invite = inviteCode == null ? "" : ",\"inviteCode\":\"" + inviteCode + "\"";
		return post("/api/users").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"pw\"" + invite + "}");
	}
}
