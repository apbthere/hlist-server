package com.abcconsulting.slme.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hlist.client.location=classpath:/test-client/")
@AutoConfigureMockMvc
class ClientAppTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void appShellIsPublic() throws Exception {
		mockMvc.perform(get("/app"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/app/"));
		mockMvc.perform(get("/app/"))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl("/app/index.html"));
		mockMvc.perform(get("/app/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("HList test client")));
		mockMvc.perform(get("/app/manifest.webmanifest"))
				.andExpect(status().isOk());
	}

	@Test
	void clientRoutesFallBackToIndexButMissingFilesDoNot() throws Exception {
		mockMvc.perform(get("/app/lists/12"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("HList test client")));
		mockMvc.perform(get("/app/login"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("HList test client")));
		mockMvc.perform(get("/app/assets/missing-000.js"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/app/missing.png"))
				.andExpect(status().isNotFound());
	}

	@Test
	void hashedAssetsAreCachedLongTerm() throws Exception {
		mockMvc.perform(get("/app/assets/app-abc123.js"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("immutable")));
	}
}
