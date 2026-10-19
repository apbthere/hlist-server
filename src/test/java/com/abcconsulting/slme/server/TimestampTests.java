package com.abcconsulting.slme.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TimestampTests {

	private static final String PASSWORD = "timestamp-password";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void listsAndListItemsCarryDatabaseTimestamps() throws Exception {
		String username = UUID.randomUUID().toString();
		mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated());
		OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);

		String list = perform(post("/api/shopping-lists"), username, "{\"name\":\"weekly\"}");
		assertEquals("Weekly", JsonPath.read(list, "$.shoppingListName"), "new list names are capitalized");
		OffsetDateTime listCreated = timestamp(list, "$.createdAt");
		assertTrue(listCreated.isAfter(before), "list createdAt is set on insert");
		timestamp(list, "$.updatedAt");
		long listId = ((Number) JsonPath.read(list, "$.shoppingListId")).longValue();

		String listed = perform(get("/api/shopping-lists"), username, null);
		assertTrue(timestamp(listed, "$.content[0].createdAt").isEqual(listCreated), "listing returns the same createdAt");

		long itemId = ((Number) JsonPath.read(perform(post("/api/shopping-lists/" + listId + "/items"), username,
				"{\"name\":\"Milk\"}"), "$.itemId")).longValue();
		String items = perform(get("/api/shopping-lists/" + listId + "/items"), username, null);
		OffsetDateTime added = timestamp(items, "$[0].createdAt");
		OffsetDateTime firstUpdate = timestamp(items, "$[0].updatedAt");
		assertFalse(added.isBefore(listCreated), "item added after the list was created");

		Thread.sleep(20);
		mockMvc.perform(patch("/api/shopping-lists/" + listId + "/items/" + itemId + "/completion")
						.with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"completed\":true}"))
				.andExpect(status().isNoContent());
		String checked = perform(get("/api/shopping-lists/" + listId + "/items"), username, null);
		assertTrue(timestamp(checked, "$[0].createdAt").isEqual(added), "createdAt doesn't change");
		assertTrue(timestamp(checked, "$[0].updatedAt").isAfter(firstUpdate), "updatedAt moves on change");
	}

	private String perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String username, String body) throws Exception {
		request.with(httpBasic(username, PASSWORD));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
	}

	private static OffsetDateTime timestamp(String json, String path) {
		return OffsetDateTime.parse(JsonPath.read(json, path));
	}
}
