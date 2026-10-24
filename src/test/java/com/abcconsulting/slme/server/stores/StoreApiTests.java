package com.abcconsulting.slme.server.stores;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StoreApiTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StoreIconRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** The tests share the development database, so the fake icon must not outlive the test. */
	@AfterEach
	void removeFakeIcon() {
		jdbcTemplate.update("DELETE FROM store_icon WHERE store_key = 'publix' AND source_url = 'https://example/icon.png'");
	}

	@Test
	void storesAndStoredIconsArePublic() throws Exception {
		mockMvc.perform(get("/api/stores"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].key").value(hasItems("publix", "costco", "aldi", "walmart", "target", "homedepot", "lowes", "wholefoods", "freshmarket")))
				.andExpect(jsonPath("$[?(@.key == 'homedepot')].iconUrl").value(hasItems("/api/store-icons/homedepot")))
				.andExpect(jsonPath("$[?(@.key == 'costco')].domains[*]").value(hasItems("costco.com", "costcobusinessdelivery.com")));

		// A stored icon is served without contacting the store.
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 7};
		repository.save(new StoreIconRepository.StoredIcon("publix", png, "image/png", "https://example/icon.png", OffsetDateTime.now()));
		mockMvc.perform(get("/api/store-icons/publix"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/png"))
				.andExpect(header().string("Cache-Control", containsString("public")))
				.andExpect(content().bytes(png));

		mockMvc.perform(get("/api/store-icons/not-a-store")).andExpect(status().isNotFound());
	}

	@Test
	void listsNameTheirStore() throws Exception {
		String username = UUID.randomUUID().toString();
		mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"pw\"}")).andExpect(status().isCreated());

		mockMvc.perform(post("/api/shopping-lists").with(httpBasic(username, "pw"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"costco run\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.store.key").value("costco"))
				.andExpect(jsonPath("$.store.name").value("Costco"));
		mockMvc.perform(post("/api/shopping-lists").with(httpBasic(username, "pw"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"weekly\"}"));

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, "pw")))
				.andExpect(jsonPath("$.content[?(@.shoppingListName == 'Costco run')].store.key").value(hasItems("costco")))
				.andExpect(jsonPath("$.content[?(@.shoppingListName == 'Weekly')].store").value(hasItems((Object) null)));
	}
}
