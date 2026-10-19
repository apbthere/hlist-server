package com.abcconsulting.slme.server;

import com.abcconsulting.slme.server.live.LiveUpdateService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LiveUpdateTests {

	private static final String PASSWORD = "live-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LiveUpdateService liveUpdateService;

	@Test
	void theStreamRequiresSignIn() throws Exception {
		mockMvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
	}

	@Test
	void changesReachTheOwnersOtherDevicesButNotOtherUsers() throws Exception {
		String alex = createUser();
		String sam = createUser();
		MockHttpServletResponse alexIpad = openStream(alex);
		MockHttpServletResponse samPhone = openStream(sam);
		assertTrue(alexIpad.getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
		assertTrue(alexIpad.getContentAsString().contains("event:ready"), "the stream starts with a ready event");

		long listId = id(perform(post("/api/shopping-lists"), alex, "{\"name\":\"Weekly\"}"), "$.shoppingListId");
		assertTrue(alexIpad.getContentAsString().contains("\"type\":\"lists\""), "a new list reaches the owner");

		String item = mockMvc.perform(post("/api/shopping-lists/" + listId + "/items")
						.with(httpBasic(alex, PASSWORD)).header("X-HList-Client", "alex-iphone")
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Milk\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String ipad = alexIpad.getContentAsString();
		assertTrue(ipad.contains("{\"type\":\"list-items\",\"listId\":" + listId + ",\"origin\":\"alex-iphone\"}"),
				"the list change names the list and the device that made it: " + ipad);

		long itemId = id(item, "$.itemId");
		mockMvc.perform(patch("/api/shopping-lists/" + listId + "/items/" + itemId + "/completion")
						.with(httpBasic(alex, PASSWORD)).contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
				.andExpect(status().isNoContent());
		assertEquals(2, count(alexIpad.getContentAsString(), "\"type\":\"list-items\""), "checking off is a list change too");

		String samSees = samPhone.getContentAsString();
		assertFalse(samSees.contains("\"type\":\"lists\""), "another user's lists stay private");
		assertFalse(samSees.contains("\"type\":\"list-items\""), "another user's items stay private");
	}

	@Test
	void sharedCatalogChangesReachEveryoneAndRolledBackChangesReachNoOne() throws Exception {
		String alex = createUser();
		String sam = createUser();
		MockHttpServletResponse alexStream = openStream(alex);
		MockHttpServletResponse samStream = openStream(sam);

		long departmentId = id(perform(post("/api/departments"), alex, "{\"name\":\"Dept-" + alex + "\"}"), "$.departmentId");
		assertTrue(alexStream.getContentAsString().contains("\"type\":\"catalog\""));
		assertTrue(samStream.getContentAsString().contains("\"type\":\"catalog\""), "departments are shared");

		perform(post("/api/items"), alex, "{\"name\":\"Uses it\",\"departmentId\":" + departmentId + "}");
		int catalogEvents = count(samStream.getContentAsString(), "\"type\":\"catalog\"");
		mockMvc.perform(delete("/api/departments/" + departmentId).with(httpBasic(alex, PASSWORD)))
				.andExpect(status().isConflict());
		assertEquals(catalogEvents, count(samStream.getContentAsString(), "\"type\":\"catalog\""),
				"a refused (rolled back) change sends nothing");
	}

	@Test
	void idleStreamsGetKeepAliveComments() throws Exception {
		MockHttpServletResponse stream = openStream(createUser());
		liveUpdateService.heartbeat();
		assertTrue(stream.getContentAsString().contains(":keep-alive"));
	}

	private MockHttpServletResponse openStream(String username) throws Exception {
		return mockMvc.perform(get("/api/events").with(httpBasic(username, PASSWORD)))
				.andExpect(request().asyncStarted())
				.andReturn().getResponse();
	}

	private String perform(MockHttpServletRequestBuilder request, String username, String body) throws Exception {
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

	private static int count(String text, String needle) {
		int count = 0;
		for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
			count++;
		}
		return count;
	}
}
