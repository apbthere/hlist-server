package com.abcconsulting.slme.server.photos;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PhotoApiTests {

	private static final String PASSWORD = "photo-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PhotoService photoService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void uploadedPhotosArePrivateAndCacheable() throws Exception {
		String owner = createUser();
		String other = createUser();
		long photoId = upload(owner, TestImages.jpegWithGps(2400, 1800));

		mockMvc.perform(get("/api/photos/" + photoId).with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_JPEG))
				.andExpect(header().string("Cache-Control", containsString("private")))
				.andExpect(header().string("Cache-Control", containsString("immutable")));
		mockMvc.perform(get("/api/photos/" + photoId + "/thumbnail").with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/photos/" + photoId).with(httpBasic(other, PASSWORD)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/photos/" + photoId)).andExpect(status().isUnauthorized());
	}

	@Test
	void uploadsAreCheckedAndLimited() throws Exception {
		String owner = createUser();
		mockMvc.perform(post("/api/photos").with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.IMAGE_JPEG).content("not a picture".getBytes()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("isn't a picture")));
		mockMvc.perform(post("/api/photos").with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.IMAGE_JPEG).content(new byte[PhotoService.MAX_UPLOAD_BYTES + 1]))
				.andExpect(status().is(413));
	}

	@Test
	void photosAttachToItemsAndUnusedOnesAreDeleted() throws Exception {
		String owner = createUser();
		long listId = id(perform(post("/api/shopping-lists"), owner, "{\"name\":\"Costco\"}"), "$.shoppingListId");
		long itemId = id(perform(post("/api/shopping-lists/" + listId + "/items"), owner, "{\"name\":\"Olive oil\"}"), "$.itemId");
		MockHttpServletResponse stream = mockMvc.perform(get("/api/events").with(httpBasic(owner, PASSWORD)))
				.andExpect(request().asyncStarted()).andReturn().getResponse();

		long first = upload(owner, TestImages.jpeg(800, 600));
		perform(put("/api/items/" + itemId + "/photo"), owner, "{\"photoId\":" + first + "}");
		mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$[0].photoId").value(first));
		mockMvc.perform(get("/api/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$[0].photoId").value(first));
		assertTrue(stream.getContentAsString().contains("{\"type\":\"list-items\",\"listId\":" + listId + "}"),
				"other devices hear about the new photo");

		// Replacing the photo deletes the old one, which no item uses any more.
		long second = upload(owner, TestImages.jpeg(600, 800));
		perform(put("/api/items/" + itemId + "/photo"), owner, "{\"photoId\":" + second + "}");
		mockMvc.perform(get("/api/photos/" + first).with(httpBasic(owner, PASSWORD))).andExpect(status().isNotFound());

		// Editing the item keeps its photo.
		perform(put("/api/shopping-lists/" + listId + "/items/" + itemId), owner, "{\"name\":\"Olive oil 2L\"}");
		mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$[0].photoId").value(second));

		mockMvc.perform(delete("/api/items/" + itemId + "/photo").with(httpBasic(owner, PASSWORD)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
				.andExpect(jsonPath("$[0].photoId").doesNotExist());
		mockMvc.perform(get("/api/photos/" + second).with(httpBasic(owner, PASSWORD))).andExpect(status().isNotFound());
	}

	@Test
	void photosCanOnlyBeAttachedByTheirOwnerToTheirItems() throws Exception {
		String owner = createUser();
		String other = createUser();
		long photoId = upload(owner, TestImages.jpeg(300, 300));
		long othersItem = id(perform(post("/api/items"), other, "{\"name\":\"Theirs\"}"), "$.itemId");
		long ownItem = id(perform(post("/api/items"), owner, "{\"name\":\"Mine\"}"), "$.itemId");

		mockMvc.perform(put("/api/items/" + othersItem + "/photo").with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"photoId\":" + photoId + "}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/items/" + othersItem + "/photo").with(httpBasic(other, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"photoId\":" + photoId + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Photo not found"));
		mockMvc.perform(put("/api/items/" + ownItem + "/photo").with(httpBasic(owner, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content("{\"photoId\":" + photoId + "}"))
				.andExpect(status().isNoContent());
	}

	@Test
	void theNightlyCleanUpDeletesOnlyOldUnusedPhotos() throws Exception {
		String owner = createUser();
		long abandoned = upload(owner, TestImages.jpeg(200, 200));
		long recent = upload(owner, TestImages.jpeg(200, 200));
		long used = upload(owner, TestImages.jpeg(200, 200));
		long itemId = id(perform(post("/api/items"), owner, "{\"name\":\"Kept\"}"), "$.itemId");
		perform(put("/api/items/" + itemId + "/photo"), owner, "{\"photoId\":" + used + "}");
		jdbcTemplate.update("UPDATE photo SET created_at = DATEADD('DAY', -2, CURRENT_TIMESTAMP) WHERE photo_id IN (?, ?)", abandoned, used);

		photoService.deleteUnusedPhotos();

		assertEquals(0, count(abandoned), "an old photo no item uses is deleted");
		assertEquals(1, count(recent), "a fresh upload gets a day to be attached");
		assertEquals(1, count(used), "a photo an item shows is kept");
	}

	private int count(long photoId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM photo WHERE photo_id = ?", Integer.class, photoId);
	}

	private long upload(String username, byte[] picture) throws Exception {
		String body = mockMvc.perform(post("/api/photos").with(httpBasic(username, PASSWORD))
						.contentType(MediaType.IMAGE_JPEG).content(picture))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return id(body, "$.photoId");
	}

	private String perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String username, String body) throws Exception {
		return mockMvc.perform(request.with(httpBasic(username, PASSWORD))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn().getResponse().getContentAsString();
	}

	private String createUser() throws Exception {
		String username = UUID.randomUUID().toString();
		mockMvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isCreated());
		return username;
	}

	private static long id(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}
}
