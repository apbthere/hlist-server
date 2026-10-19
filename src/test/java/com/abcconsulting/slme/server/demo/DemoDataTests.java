package com.abcconsulting.slme.server.demo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Its own database: the demo only seeds a database without accounts, and other tests share theirs.
@SpringBootTest(properties = {"hlist.demo=true", "hlist.demo.photos=false",
		"spring.datasource.url=jdbc:h2:mem:demo-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc
class DemoDataTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DemoData demoData;

	@Test
	void anEmptyDatabaseGetsTheDemoAccountAndItsLists() throws Exception {
		String lists = mockMvc.perform(get("/api/shopping-lists").with(httpBasic(DemoData.USERNAME, DemoData.PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].shoppingListName").value(contains("Costco Run", "Publix Weekly", "Target", "Costco")))
				.andExpect(jsonPath("$.content[3].completed").value(true))
				.andExpect(jsonPath("$.content[0].store.name").value("Costco"))
				.andReturn().getResponse().getContentAsString();

		// The past Costco trip is two weeks old, and the lists are dated in order.
		List<String> created = JsonPath.read(lists, "$.content[*].createdAt");
		OffsetDateTime newest = OffsetDateTime.parse(created.getFirst());
		OffsetDateTime oldest = OffsetDateTime.parse(created.getLast());
		assertTrue(oldest.isBefore(newest.minusDays(13)), created.toString());

		Number costcoRun = JsonPath.read(lists, "$.content[0].shoppingListId");
		mockMvc.perform(get("/api/shopping-lists/" + costcoRun + "/items").with(httpBasic(DemoData.USERNAME, DemoData.PASSWORD)))
				.andExpect(jsonPath("$[?(@.itemName == 'Eggs')].completed").value(contains(true)))
				.andExpect(jsonPath("$[?(@.itemName == 'Greek Yogurt')].quantity").value(contains(2)));
		mockMvc.perform(get("/api/items").with(httpBasic(DemoData.USERNAME, DemoData.PASSWORD)))
				.andExpect(jsonPath("$[*].itemName").value(hasItem("Oat Milk")));
	}

	@Test
	void aDatabaseWithAccountsIsLeftAlone() throws Exception {
		demoData.run(null); // the account from the first start exists now

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(DemoData.USERNAME, DemoData.PASSWORD)))
				.andExpect(jsonPath("$.totalElements").value(DemoData.LISTS.size()));
	}
}
