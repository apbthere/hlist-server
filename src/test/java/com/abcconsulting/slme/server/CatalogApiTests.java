package com.abcconsulting.slme.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiTests {

	private static final String PASSWORD = "catalog-password";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void itemsCanBeCreatedUpdatedAndDeletedByTheirOwnerOnly() throws Exception {
		String owner = createUser();
		String other = createUser();
		long departmentId = id(mockMvc.perform(json(post("/api/departments"), owner, "{\"name\":\"Dept-" + owner + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.departmentId");

		String created = mockMvc.perform(json(post("/api/items"), owner,
						"{\"name\":\"Milk\",\"quantity\":2,\"departmentId\":" + departmentId + "}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.itemName").value("Milk"))
				.andExpect(jsonPath("$.quantity").value(2))
				.andReturn().getResponse().getContentAsString();
		long itemId = id(created, "$.itemId");
		OffsetDateTime createdAt = OffsetDateTime.parse(JsonPath.read(created, "$.createdAt"));
		OffsetDateTime firstUpdate = OffsetDateTime.parse(JsonPath.read(created, "$.updatedAt"));
		Thread.sleep(20);

		String updated = mockMvc.perform(json(put("/api/items/" + itemId), owner, "{\"name\":\"Oat milk\",\"quantity\":3,\"comment\":\"Barista\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.itemName").value("Oat milk"))
				.andExpect(jsonPath("$.quantity").value(3))
				.andExpect(jsonPath("$.departmentId").doesNotExist())
				.andExpect(jsonPath("$.itemComment").value("Barista"))
				.andReturn().getResponse().getContentAsString();
		assertTrue(OffsetDateTime.parse(JsonPath.read(updated, "$.createdAt")).isEqual(createdAt), "createdAt is kept");
		assertTrue(OffsetDateTime.parse(JsonPath.read(updated, "$.updatedAt")).isAfter(firstUpdate), "updatedAt moves on edit");
		mockMvc.perform(get("/api/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$[0].updatedAt").value(JsonPath.<String>read(updated, "$.updatedAt")));

		mockMvc.perform(json(put("/api/items/" + itemId), other, "{\"name\":\"Stolen\"}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/items/" + itemId).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isNotFound());
		mockMvc.perform(json(post("/api/items"), owner, "{\"name\":\"Ghost\",\"departmentId\":999999}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Department not found"));

		mockMvc.perform(delete("/api/items/" + itemId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void deletingAnItemRemovesItFromListsAndUpdatesTheirCompletion() throws Exception {
		String owner = createUser();
		long listId = id(mockMvc.perform(json(post("/api/shopping-lists"), owner, "{\"name\":\"Weekly\"}"))
				.andReturn().getResponse().getContentAsString(), "$.shoppingListId");
		long doneId = id(mockMvc.perform(json(post("/api/shopping-lists/" + listId + "/items"), owner, "{\"name\":\"Bread\"}"))
				.andReturn().getResponse().getContentAsString(), "$.itemId");
		long openId = id(mockMvc.perform(json(post("/api/shopping-lists/" + listId + "/items"), owner, "{\"name\":\"Eggs\"}"))
				.andReturn().getResponse().getContentAsString(), "$.itemId");
		mockMvc.perform(json(patch("/api/shopping-lists/" + listId + "/items/" + doneId + "/completion"), owner, "{\"completed\":true}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/items/" + openId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].itemName").value("Bread"));
		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$.content[0].completed").value(true));
	}

	@Test
	void departmentsAndBrandsCanBeRenamedAndDeletedWhenUnused() throws Exception {
		String owner = createUser();
		for (String type : new String[] {"department", "brand"}) {
			String collection = "/api/" + type + "s";
			long id = id(mockMvc.perform(json(post(collection), owner, "{\"name\":\"Old-" + owner + "\"}"))
					.andReturn().getResponse().getContentAsString(), "$." + type + "Id");

			mockMvc.perform(json(put(collection + "/" + id), owner, "{\"name\":\"New-" + owner + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$." + type + "Name").value("New-" + owner));

			long itemId = id(mockMvc.perform(json(post("/api/items"), owner, "{\"name\":\"Uses it\",\"" + type + "Id\":" + id + "}"))
					.andReturn().getResponse().getContentAsString(), "$.itemId");
			mockMvc.perform(delete(collection + "/" + id).with(csrf()).with(httpBasic(owner, PASSWORD)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.message", containsString("is used by 1 item")));

			mockMvc.perform(delete("/api/items/" + itemId).with(httpBasic(owner, PASSWORD)))
					.andExpect(status().isNoContent());
			mockMvc.perform(delete(collection + "/" + id).with(httpBasic(owner, PASSWORD)))
					.andExpect(status().isNoContent());
			mockMvc.perform(json(put(collection + "/" + id), owner, "{\"name\":\"Gone\"}"))
					.andExpect(status().isNotFound());
		}
	}

	private String createUser() throws Exception {
		String username = UUID.randomUUID().toString();
		mockMvc.perform(post("/api/users").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated());
		return username;
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String username, String body) {
		return request.with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static long id(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}
}
