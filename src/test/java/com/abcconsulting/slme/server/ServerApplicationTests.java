package com.abcconsulting.slme.server;

import com.abcconsulting.slme.server.model.Department;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.model.UserAccount;
import com.abcconsulting.slme.server.repository.ItemRepository;
import com.abcconsulting.slme.server.repository.UserAccountRepository;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import jakarta.servlet.http.Cookie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ServerApplicationTests {

	@Autowired
	private ShoppingListService shoppingListService;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UserDetailsService userDetailsService;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void manualTestingUiIsPublic() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl("index.html"));
	}

	@Test
	void listsComeNewestFirst() {
		String username = UUID.randomUUID().toString();
		userAccountService.createUser(username, "test-password");
		ShoppingList oldest = shoppingListService.addShoppingList("Publix", username);
		ShoppingList middle = shoppingListService.addShoppingList("Costco", username);
		ShoppingList newest = shoppingListService.addShoppingList("Aldi", username);
		jdbcTemplate.update("UPDATE shopping_list SET created_at = DATEADD('DAY', -2, created_at) WHERE shopping_list_id = ?", oldest.shoppingListId());

		List<Long> firstPage = shoppingListService.getShoppingListsByUser(username, PageRequest.of(0, 2)).getContent().stream()
				.map(ShoppingList::shoppingListId).toList();
		List<Long> secondPage = shoppingListService.getShoppingListsByUser(username, PageRequest.of(1, 2)).getContent().stream()
				.map(ShoppingList::shoppingListId).toList();

		assertEquals(List.of(newest.shoppingListId(), middle.shoppingListId()), firstPage);
		assertEquals(List.of(oldest.shoppingListId()), secondPage);
	}

	@Test
	void shoppingListServiceSupportsShoppingListAndItemOperations() {
		String username = UUID.randomUUID().toString();
		userAccountService.createUser(username, "test-password");

		ShoppingList shoppingList = shoppingListService.addShoppingList("Weekly", username);
		shoppingListService.addShoppingList("Monthly", username);

		Page<ShoppingList> shoppingLists = shoppingListService.getShoppingListsByUser(
				username, PageRequest.of(0, 1));
		assertEquals(1, shoppingLists.getContent().size());
		assertEquals(2, shoppingLists.getTotalElements());

		Department department = shoppingListService.addDepartment("Dairy");
		Item item = shoppingListService.addShoppingItem(
				shoppingList.shoppingListId(), "Milk", department.departmentId(), null, null, username);
		assertNotNull(item.itemId());
		var shoppingItems = shoppingListService.getShoppingItemsByList(
				shoppingList.shoppingListId(), username);
		assertEquals(1, shoppingItems.size());
		assertFalse(shoppingItems.get(0).completed());
		assertTrue(shoppingListService.updateShoppingItemCompletion(
				shoppingList.shoppingListId(), item.itemId(), username, true));
		assertTrue(shoppingListService.getShoppingItemsByList(
				shoppingList.shoppingListId(), username).get(0).completed());

		Item updatedItem = new Item(
				item.itemId(), "Oat milk", department.departmentId(), null, "Unsweetened", username);
		assertEquals("Oat milk", shoppingListService.updateShoppingItem(
				shoppingList.shoppingListId(), updatedItem, username).itemName());

		assertTrue(shoppingListService.deleteShoppingItemFromList(
				shoppingList.shoppingListId(), item.itemId(), username));
		assertTrue(shoppingListService.getShoppingItemsByList(
				shoppingList.shoppingListId(), username).isEmpty());
		assertTrue(itemRepository.existsById(item.itemId()));
		assertFalse(shoppingListService.deleteShoppingItemFromList(
				shoppingList.shoppingListId(), item.itemId(), username));
	}

	@Test
	void userAccountServiceEncodesAndChangesPasswords() {
		String username = UUID.randomUUID().toString();
		String initialPassword = "initial-password";
		String newPassword = "new-password";

		userAccountService.createUser(username, initialPassword);
		UserAccount createdUser = userAccountRepository.findById(username).orElseThrow();
		assertNotEquals(initialPassword, createdUser.password());
		assertTrue(passwordEncoder.matches(initialPassword, createdUser.password()));
		assertTrue(passwordEncoder.matches(
				initialPassword, userDetailsService.loadUserByUsername(username).getPassword()));

		assertThrows(IllegalArgumentException.class, () -> userAccountService.changeUserPassword(
				username, "incorrect-password", newPassword));

		userAccountService.changeUserPassword(username, initialPassword, newPassword);
		UserAccount changedUser = userAccountRepository.findById(username).orElseThrow();
		assertTrue(passwordEncoder.matches(newPassword, changedUser.password()));
		assertFalse(passwordEncoder.matches(initialPassword, changedUser.password()));
	}

	@Test
	void webServicesExposeShoppingAndUserOperationsWithAuthentication() throws Exception {
		String username = UUID.randomUUID().toString();
		String initialPassword = "initial-password";
		String newPassword = "new-password";

		mockMvc.perform(get("/api/shopping-lists"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/users").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + initialPassword + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username));

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));

		mockMvc.perform(post("/api/shopping-lists")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Weekly\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.completed").value(false));
		Long shoppingListId = jdbcTemplate.queryForObject(
				"SELECT shopping_list_id FROM shopping_list WHERE username = ? AND shopping_list_name = ?",
				Long.class, username, "Weekly");

		mockMvc.perform(patch("/api/shopping-lists/" + shoppingListId + "/completion")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"completed\":true}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].completed").value(true));

		String departmentName = "Dairy-" + username;
		mockMvc.perform(post("/api/departments")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + departmentName + "\"}"))
				.andExpect(status().isCreated());
		Long departmentId = jdbcTemplate.queryForObject(
				"SELECT department_id FROM department WHERE department_name = ?",
				Long.class, departmentName);

		String brandName = "Brand-" + username;
		mockMvc.perform(post("/api/brands")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + brandName + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.brandName").value(brandName));
		Long brandId = jdbcTemplate.queryForObject(
				"SELECT brand_id FROM brand WHERE brand_name = ?",
				Long.class, brandName);
		mockMvc.perform(get("/api/brands").with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.brandId == " + brandId + ")].brandName").value(brandName));

		mockMvc.perform(post("/api/shopping-lists/" + shoppingListId + "/items")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Milk\",\"quantity\":3,\"departmentId\":" + departmentId
						+ ",\"brandId\":" + brandId + ",\"comment\":\"Whole\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.quantity").value(3))
				.andExpect(jsonPath("$.brandId").value(brandId));
		Long itemId = jdbcTemplate.queryForObject(
				"SELECT item_id FROM item WHERE username = ? AND item_name = ?",
				Long.class, username, "Milk");

		mockMvc.perform(get("/api/shopping-lists/" + shoppingListId + "/items")
				.with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].itemName").value("Milk"))
				.andExpect(jsonPath("$[0].quantity").value(3))
				.andExpect(jsonPath("$[0].completed").value(false));

		mockMvc.perform(patch("/api/shopping-lists/" + shoppingListId + "/items/" + itemId + "/completion")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"completed\":true}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/shopping-lists/" + shoppingListId + "/items")
				.with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].completed").value(true));

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, initialPassword)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].completed").value(true));

		mockMvc.perform(put("/api/shopping-lists/" + shoppingListId + "/items/" + itemId)
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Oat milk\",\"departmentId\":" + departmentId
						+ ",\"brandId\":" + brandId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.itemName").value("Oat milk"))
				.andExpect(jsonPath("$.brandId").value(brandId));

		mockMvc.perform(delete("/api/shopping-lists/" + shoppingListId + "/items/" + itemId)
				.with(httpBasic(username, initialPassword)))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/users/me/password")
				.with(httpBasic(username, initialPassword))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + initialPassword
						+ "\",\"newPassword\":\"" + newPassword + "\"}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, initialPassword)))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/shopping-lists").with(httpBasic(username, newPassword)))
				.andExpect(status().isOk());
	}

	@Test
	void signInPagesAndTheirScriptsArePublic() throws Exception {
		for (String path : new String[] {"/login.html", "/console.html", "/api.js", "/ui-choice.js"}) {
			mockMvc.perform(get(path)).andExpect(status().isOk());
		}
	}

	@Test
	void buildInfoIsPublicAndReportsDevOutsideDocker() throws Exception {
		mockMvc.perform(get("/api/version"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serverCommit").value("dev"))
				.andExpect(jsonPath("$.clientCommit").value("dev"));
	}

	@Test
	void teslaPublicKeyIsPublic() throws Exception {
		mockMvc.perform(get("/.well-known/appspecific/com.tesla.3p.public-key.pem"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.startsWith("-----BEGIN PUBLIC KEY-----")));
	}

	@Test
	void registrationIsOpenWithoutAnInviteCode() throws Exception {
		mockMvc.perform(get("/api/auth/registration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inviteRequired").value(false));
	}

	@Test
	void browserLoginUsesSessionCookieWithCsrfProtection() throws Exception {
		String username = UUID.randomUUID().toString();
		String password = "browser-password";
		userAccountService.createUser(username, password);

		mockMvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"));

		mockMvc.perform(post("/api/auth/login")
				.param("username", username)
				.param("password", password))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/auth/login").with(csrf())
				.param("username", username)
				.param("password", "wrong-password"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));

		MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
				.param("username", username)
				.param("password", password))
				.andExpect(status().isNoContent())
				.andExpect(cookie().doesNotExist("remember-me"))
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		assertNotNull(session);

		mockMvc.perform(get("/api/users/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username));

		mockMvc.perform(post("/api/shopping-lists").session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Weekly\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/shopping-lists").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Weekly\"}"))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/users/me").session(session))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rememberMeKeepsBrowserSignedInUntilLogout() throws Exception {
		String username = UUID.randomUUID().toString();
		String password = "remember-password";
		userAccountService.createUser(username, password);

		Cookie rememberMe = rememberMeCookie(browserLogin(username, password));
		assertEquals(1, rememberMeTokenCount(username));

		// A new browser session (e.g. the home-screen app was closed) signs in from the cookie alone.
		MvcResult restored = mockMvc.perform(get("/api/users/me").cookie(rememberMe))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username))
				.andReturn();
		Cookie rotated = rememberMeCookie(restored);
		assertNotEquals(rememberMe.getValue(), rotated.getValue());

		mockMvc.perform(post("/api/auth/logout").cookie(rotated).with(csrf()))
				.andExpect(status().isNoContent());
		assertEquals(0, rememberMeTokenCount(username));
		mockMvc.perform(get("/api/users/me").cookie(rotated))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void passwordChangeSignsOutOtherRememberedDevices() throws Exception {
		String username = UUID.randomUUID().toString();
		String initialPassword = "initial-password";
		String newPassword = "new-password";
		userAccountService.createUser(username, initialPassword);

		Cookie phone = rememberMeCookie(browserLogin(username, initialPassword));
		Cookie tablet = rememberMeCookie(browserLogin(username, initialPassword));

		MvcResult passwordChange = mockMvc.perform(put("/api/users/me/password").cookie(phone).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + initialPassword
						+ "\",\"newPassword\":\"" + newPassword + "\"}"))
				.andExpect(status().isNoContent())
				.andReturn();
		Cookie renewedPhone = rememberMeCookie(passwordChange);
		assertEquals(1, rememberMeTokenCount(username));

		mockMvc.perform(get("/api/users/me").cookie(tablet))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/users/me").cookie(renewedPhone))
				.andExpect(status().isOk());
	}

	@Test
	void parallelRequestsWithTheSameRememberMeCookieDontSignTheUserOut() throws Exception {
		String username = UUID.randomUUID().toString();
		String password = "parallel-password";
		userAccountService.createUser(username, password);
		Cookie rememberMe = rememberMeCookie(browserLogin(username, password));

		// The app was closed (session gone) and the next page load fires several requests at once.
		String[] paths = {"/api/users/me", "/api/shopping-lists", "/api/departments", "/api/brands", "/api/items", "/api/users/me"};
		ExecutorService executor = Executors.newFixedThreadPool(paths.length);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<MvcResult>> results = new ArrayList<>();
			for (String path : paths) {
				results.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(get(path).cookie(rememberMe)).andReturn();
				}));
			}
			start.countDown();
			Cookie latest = null;
			for (Future<MvcResult> result : results) {
				MvcResult response = result.get(30, TimeUnit.SECONDS);
				assertEquals(200, response.getResponse().getStatus(), "every parallel request stays signed in");
				latest = rememberMeCookie(response);
			}
			assertEquals(1, rememberMeTokenCount(username), "the remember-me login survives");

			// Whatever cookie the browser kept last still signs in on the next launch.
			mockMvc.perform(get("/api/users/me").cookie(latest))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.username").value(username));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void aRememberMeCookieReplacedTwiceIsStillTreatedAsTheft() throws Exception {
		String username = UUID.randomUUID().toString();
		String password = "theft-password";
		userAccountService.createUser(username, password);
		Cookie original = rememberMeCookie(browserLogin(username, password));
		Cookie second = rememberMeCookie(mockMvc.perform(get("/api/users/me").cookie(original)).andReturn());
		rememberMeCookie(mockMvc.perform(get("/api/users/me").cookie(second)).andReturn());

		mockMvc.perform(get("/api/users/me").cookie(original))
				.andExpect(status().isUnauthorized());
		assertEquals(0, rememberMeTokenCount(username), "a stolen cookie revokes the user's remember-me logins");
	}

	private MvcResult browserLogin(String username, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").with(csrf())
				.param("username", username)
				.param("password", password)
				.param("remember-me", "true"))
				.andExpect(status().isNoContent())
				.andReturn();
	}

	private static Cookie rememberMeCookie(MvcResult result) {
		return Arrays.stream(result.getResponse().getCookies())
				.filter(cookie -> cookie.getName().equals("remember-me") && cookie.getMaxAge() != 0)
				.reduce((first, second) -> second)
				.orElseThrow(() -> new AssertionError("No remember-me cookie issued"));
	}

	private int rememberMeTokenCount(String username) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Integer.class, username);
	}

}
