package com.abcconsulting.slme.server.assistant;

import com.abcconsulting.slme.server.photos.PhotoImporter;
import com.abcconsulting.slme.server.photos.ProductPhotoFinder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class McpTests {

    private static final String PASSWORD = "mcp-password";

    @Autowired
    private MockMvc mockMvc;

    // No outside fetches in tests: product pages and Open Food Facts are stand-ins.
    @MockitoBean
    private PhotoImporter photoImporter;

    @MockitoBean
    private ProductPhotoFinder photoFinder;

    @BeforeEach
    void noPhotosOnline() {
        when(photoImporter.fetchPicture(any())).thenThrow(new IllegalArgumentException("That address can't be used."));
        when(photoFinder.find(any(), any())).thenReturn(List.of());
    }

    @Test
    void newItemsWithoutAProductPageGetTheBestProductDatabasePhoto() throws Exception {
        String token = token(createUser());
        String image = "https://images.example/bounty.jpg";
        when(photoFinder.find("Paper Towels", "Bounty")).thenReturn(List.of(
                new ProductPhotoFinder.Suggestion("Paper Towels", "Bounty", image, image)));
        doReturn(new PhotoImporter.Fetched(jpeg(), null, null)).when(photoImporter).fetchPicture(image);
        long listId = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco")),
                "$.listId")).longValue();

        String added = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Paper Towels", "brand", "Bounty"));

        assertEquals(true, JsonPath.read(added, "$.item.hasPhoto"));
        verify(photoFinder).find("Paper Towels", "Bounty");
    }

    @Test
    void productDatabasePhotosOfOtherBrandsAreNotUsed() throws Exception {
        String token = token(createUser());
        String image = "https://images.example/scott.jpg";
        when(photoFinder.find(any(), any())).thenReturn(List.of(
                new ProductPhotoFinder.Suggestion("Toilet paper", null, image, image),
                new ProductPhotoFinder.Suggestion("Toilet Paper", "Cottonelle, Kimberly Clark", image, image)));
        doReturn(new PhotoImporter.Fetched(jpeg(), null, null)).when(photoImporter).fetchPicture(image);
        long listId = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco")),
                "$.listId")).longValue();

        assertEquals(false, JsonPath.read(tool(token, "add_item_to_list", Map.of("listId", listId,
                "name", "Toilet Paper", "brand", "Kirkland Signature")), "$.item.hasPhoto"));
        assertEquals(false, JsonPath.read(tool(token, "add_item_to_list", Map.of("listId", listId,
                "name", "Tissues")), "$.item.hasPhoto"));
        String tissues = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Toilet Paper", "brand", "Kirkland Signature", "allowDuplicate", true));
        assertTrue(JsonPath.<String>read(tissues, "$.warnings[0]").contains("\"Kirkland Signature Toilet Paper costco.com\""));
        assertTrue(JsonPath.<String>read(tissues, "$.warnings[0]").contains("set_item_photo"));
        assertTrue(ShoppingTools.sameBrand("Kirkland", "Kirkland Signature"));
        assertTrue(ShoppingTools.sameBrand("Cottonelle • Kimberly Clark", "cottonelle"));
        assertFalse(ShoppingTools.sameBrand("Scott", "Kirkland Signature"));
    }

    @Test
    void productPagesGivePhotosToItemsAlreadyOnTheList() throws Exception {
        String token = token(createUser());
        String page = "https://www.costco.com/p/-/bounty-paper-towels/4000346087";
        long listId = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco")),
                "$.listId")).longValue();
        String added = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Paper Towels", "brand", "Bounty"));
        assertEquals(false, JsonPath.read(added, "$.item.hasPhoto"));
        doReturn(new PhotoImporter.Fetched(jpeg(), "Bounty Paper Towels", "Bounty")).when(photoImporter).fetchPicture(page);

        String again = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Paper Towels", "productUrl", page));
        assertEquals("already_on_list", JsonPath.read(again, "$.outcome"));
        assertEquals(true, JsonPath.read(again, "$.item.hasPhoto"));

        String replaced = tool(token, "set_item_photo", Map.of("listId", listId,
                "itemId", JsonPath.read(added, "$.item.itemId"), "productUrl", page));
        assertEquals(true, JsonPath.read(replaced, "$.hasPhoto"));
        toolError(token, "set_item_photo", Map.of("listId", listId, "itemId", 999999, "productUrl", page));
    }

    @Test
    void accessTokensOnlyWorkForMcpAndStopWorkingWhenRemoved() throws Exception {
        String user = createUser();
        String created = mockMvc.perform(post("/api/users/me/access-tokens").with(httpBasic(user, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Claude\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.details.label").value("Claude"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        long id = ((Number) JsonPath.read(created, "$.details.id")).longValue();
        assertTrue(token.startsWith("hlist_"));

        rpc(token, "ping", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.result").isMap());
        mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/access-tokens").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        rpc("hlist_wrong", "ping", Map.of()).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/access-tokens").with(httpBasic(user, PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());

        String other = createUser();
        mockMvc.perform(delete("/api/users/me/access-tokens/" + id).with(httpBasic(other, PASSWORD)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/users/me/access-tokens/" + id).with(httpBasic(user, PASSWORD)))
                .andExpect(status().isNoContent());
        rpc(token, "ping", Map.of()).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsTheTokenWithoutBearer() throws Exception {
        String token = token(createUser());
        mockMvc.perform(post("/mcp").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/mcp").header("Authorization", "hlist_wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/items").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void speaksTheMcpHandshake() throws Exception {
        String token = token(createUser());
        rpc(token, "initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.capabilities.tools").isMap())
                .andExpect(jsonPath("$.result.instructions", containsString("search_item_history")));
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/mcp").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        rpc(token, "tools/list", Map.of())
                .andExpect(jsonPath("$.result.tools[*].name", hasItems(
                        "list_shopping_lists", "get_list_items", "search_item_history", "add_item_to_list")));
        rpc(token, "nonsense", Map.of()).andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void productPagesOfOtherBrandsGiveNoPhoto() throws Exception {
        String token = token(createUser());
        String dairyPure = "https://app.example/costco/16377-half-half-quart";
        String unbranded = "https://app.example/costco/16378-half-half";
        String landOLakes = "https://app.example/costco/16379-land-o-lakes-half-half";
        doReturn(new PhotoImporter.Fetched(jpeg(), "Half & Half", "DairyPure")).when(photoImporter).fetchPicture(dairyPure);
        doReturn(new PhotoImporter.Fetched(jpeg(), "Half & Half Quart", null)).when(photoImporter).fetchPicture(unbranded);
        doReturn(new PhotoImporter.Fetched(jpeg(), "Land O Lakes Half & Half", null)).when(photoImporter).fetchPicture(landOLakes);
        long listId = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco")),
                "$.listId")).longValue();

        String wrong = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Half & Half",
                "brand", "Land O'Lakes", "productUrl", dairyPure));
        assertEquals(false, JsonPath.read(wrong, "$.item.hasPhoto"));
        assertTrue(JsonPath.<String>read(wrong, "$.warnings[0]").contains("for DairyPure, not Land O'Lakes"));
        Object itemId = JsonPath.read(wrong, "$.item.itemId");
        toolError(token, "set_item_photo", Map.of("listId", listId, "itemId", itemId, "productUrl", dairyPure))
                .andExpect(jsonPath("$.result.content[0].text", containsString("DairyPure")));

        String unsure = tool(token, "set_item_photo", Map.of("listId", listId, "itemId", itemId, "productUrl", unbranded));
        assertEquals(true, JsonPath.read(unsure, "$.hasPhoto"));
        String doubtful = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Half & Half",
                "brand", "Land O'Lakes", "productUrl", unbranded, "allowDuplicate", true));
        assertEquals(true, JsonPath.read(doubtful, "$.item.hasPhoto"));
        assertTrue(JsonPath.<String>read(doubtful, "$.warnings[0]").contains("doesn't name Land O'Lakes"));
        String right = tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Half & Half",
                "brand", "Land O'Lakes", "productUrl", landOLakes, "allowDuplicate", true));
        assertTrue(JsonPath.<List<?>>read(right, "$.warnings").isEmpty());

        // A past item copied as another brand doesn't keep its photo.
        String otherBrand = tool(token, "add_item_to_list", Map.of("listId", listId,
                "fromItemId", JsonPath.read(right, "$.item.itemId"), "brand", "Oak Farms", "allowDuplicate", true));
        assertEquals("Oak Farms", JsonPath.read(otherBrand, "$.item.brand"));
        assertEquals(false, JsonPath.read(otherBrand, "$.item.hasPhoto"));
    }

    @Test
    void addsNewItemsCopiesPastOnesAndSkipsDuplicates() throws Exception {
        String user = createUser();
        String token = token(user);
        String department = "Paper-" + user;
        mockMvc.perform(post("/api/departments").with(httpBasic(user, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + department + "\"}"))
                .andExpect(status().isCreated());

        long lastWeek = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "costco")),
                "$.listId")).longValue();
        String added = tool(token, "add_item_to_list", Map.of("listId", lastWeek, "name", "Paper Towels",
                "brand", "Kirkland Signature", "department", department, "quantity", 2,
                "productUrl", "http://localhost/not-allowed"));
        assertEquals("added", JsonPath.read(added, "$.outcome"));
        assertEquals("Kirkland Signature", JsonPath.read(added, "$.item.brand"));
        assertEquals(department, JsonPath.read(added, "$.item.department"));
        assertFalse(JsonPath.<List<?>>read(added, "$.warnings").isEmpty(), "the photo couldn't be fetched");

        String again = tool(token, "add_item_to_list", Map.of("listId", lastWeek, "name", "paper towels"));
        assertEquals("already_on_list", JsonPath.read(again, "$.outcome"));
        long towelsId = ((Number) JsonPath.read(added, "$.item.itemId")).longValue();
        // Something still to buy, so checking off the towels doesn't mark the list done.
        tool(token, "add_item_to_list", Map.of("listId", lastWeek, "name", "Dish Soap"));
        mockMvc.perform(patch("/api/shopping-lists/" + lastWeek + "/items/" + towelsId + "/completion")
                        .with(httpBasic(user, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNoContent());
        assertEquals("unchecked", JsonPath.read(
                tool(token, "add_item_to_list", Map.of("listId", lastWeek, "name", "Paper Towels")), "$.outcome"));

        long thisWeek = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco run")),
                "$.listId")).longValue();
        String lists = tool(token, "list_shopping_lists", Map.of("search", "costco"));
        assertEquals(List.of((int) thisWeek, (int) lastWeek), JsonPath.read(lists, "$[*].listId"));
        assertEquals("Costco", JsonPath.read(lists, "$[0].store"));

        String history = tool(token, "search_item_history", Map.of("query", "towel", "store", "costco"));
        assertEquals(1, JsonPath.<List<?>>read(history, "$").size());
        assertEquals(List.of("Costco"), JsonPath.read(history, "$[0].stores"));
        assertTrue(JsonPath.<List<?>>read(tool(token, "search_item_history",
                Map.of("query", "towel", "store", "publix")), "$").isEmpty());
        assertTrue(JsonPath.<List<?>>read(tool(token, "search_item_history", Map.of("query", "milk")), "$").isEmpty());

        String copied = tool(token, "add_item_to_list", Map.of("listId", thisWeek,
                "fromItemId", JsonPath.read(history, "$[0].itemId")));
        assertEquals("added", JsonPath.read(copied, "$.outcome"));
        assertEquals("Paper Towels", JsonPath.read(copied, "$.item.name"));
        assertEquals(2, (int) JsonPath.read(copied, "$.item.quantity"));
        assertEquals(department, JsonPath.read(copied, "$.item.department"));
        assertEquals(1, JsonPath.<List<?>>read(tool(token, "get_list_items", Map.of("listId", thisWeek)), "$").size());

        // A new item named like a past one gets that one's department.
        String duplicate = tool(token, "add_item_to_list", Map.of("listId", thisWeek, "name", "PAPER TOWELS",
                "allowDuplicate", true));
        assertEquals(department, JsonPath.read(duplicate, "$.item.department"));

        toolError(token, "add_item_to_list", Map.of("listId", thisWeek, "name", "Soap", "department", "Nowhere"))
                .andExpect(jsonPath("$.result.content[0].text", containsString(department)));
        String stranger = token(createUser());
        toolError(stranger, "get_list_items", Map.of("listId", thisWeek))
                .andExpect(jsonPath("$.result.content[0].text", containsString("No list")));
        toolError(stranger, "add_item_to_list", Map.of("listId", thisWeek, "name", "Stolen"));
    }

    @Test
    void listsMarkedDoneAreOnlyAddedToWhenReopened() throws Exception {
        String user = createUser();
        String token = token(user);
        long listId = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Whole Foods")),
                "$.listId")).longValue();
        long beetsId = ((Number) JsonPath.read(tool(token, "add_item_to_list", Map.of("listId", listId, "name", "Beets")),
                "$.item.itemId")).longValue();
        // Checking off the last item marks the list done.
        mockMvc.perform(patch("/api/shopping-lists/" + listId + "/items/" + beetsId + "/completion")
                        .with(httpBasic(user, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNoContent());
        long favoriteId = ((Number) JsonPath.read(tool(token, "save_favorite", Map.of("name", "Coffee Beans")),
                "$.favoriteId")).longValue();

        toolError(token, "add_item_to_list", Map.of("listId", listId, "name", "Coffee Beans"))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"Whole Foods\" is marked done")));
        toolError(token, "add_item_to_list", Map.of("listId", listId, "fromFavoriteId", favoriteId))
                .andExpect(jsonPath("$.result.content[0].text", containsString("reopenList")));
        assertEquals(1, JsonPath.<List<?>>read(tool(token, "get_list_items", Map.of("listId", listId)), "$").size());

        assertEquals("added", JsonPath.read(tool(token, "add_item_to_list",
                Map.of("listId", listId, "fromFavoriteId", favoriteId, "reopenList", true)), "$.outcome"));
        assertEquals(List.of((int) listId), JsonPath.read(tool(token, "list_shopping_lists", Map.of()), "$[*].listId"));
    }

    @Test
    void favoritesRememberPricesAndGoOnTheCheapestStoresList() throws Exception {
        String token = token(createUser());
        long costco = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Costco")),
                "$.listId")).longValue();
        long publix = ((Number) JsonPath.read(tool(token, "create_shopping_list", Map.of("name", "Publix run")),
                "$.listId")).longValue();
        String past = tool(token, "add_item_to_list", Map.of("listId", costco, "name", "Coffee Beans", "brand", "Lavazza"));

        String saved = tool(token, "save_favorite", Map.of("fromItemId", JsonPath.read(past, "$.item.itemId"),
                "category", "coffee"));
        long favoriteId = ((Number) JsonPath.read(saved, "$.favoriteId")).longValue();
        assertEquals("Coffee", JsonPath.read(saved, "$.category"));
        assertEquals("Lavazza", JsonPath.read(saved, "$.brand"));
        assertEquals(List.of("Costco"), JsonPath.read(saved, "$.stores[*].store"));

        tool(token, "record_store_price", Map.of("favoriteId", favoriteId, "store", "costco", "price", 21.99, "size", "2.2 lb"));
        tool(token, "record_store_price", Map.of("favoriteId", favoriteId, "store", "Publix", "price", "$15.49"));
        tool(token, "record_store_price", Map.of("favoriteId", favoriteId, "store", "Aldi", "price", 9));
        String priced = tool(token, "record_store_price", Map.of("favoriteId", favoriteId, "store", "Aldi", "available", false));
        assertEquals(List.of("Publix", "Costco"), JsonPath.read(priced, "$.stores[*].store"));
        assertEquals(List.of((int) publix, (int) costco), JsonPath.read(priced, "$.stores[*].openListId"));
        assertEquals("2.2 lb", JsonPath.read(priced, "$.stores[1].size"));

        String found = tool(token, "search_favorites", Map.of("query", "coffee"));
        assertEquals(List.of("Coffee"), JsonPath.read(found, "$.categories"));
        assertEquals(List.of((int) favoriteId), JsonPath.read(found, "$.favorites[*].favoriteId"));
        assertTrue(JsonPath.<List<?>>read(tool(token, "search_favorites", Map.of("query", "sake")), "$.favorites").isEmpty());

        String added = tool(token, "add_item_to_list", Map.of("listId", publix, "fromFavoriteId", favoriteId));
        assertEquals("added", JsonPath.read(added, "$.outcome"));
        assertEquals("Lavazza", JsonPath.read(added, "$.item.brand"));
        assertEquals("already_on_list", JsonPath.read(
                tool(token, "add_item_to_list", Map.of("listId", publix, "fromFavoriteId", favoriteId)), "$.outcome"));

        toolError(token, "record_store_price", Map.of("favoriteId", favoriteId, "store", "Publix", "price", "cheap"));
        toolError(token, "add_item_to_list", Map.of("listId", publix, "fromFavoriteId", 999999))
                .andExpect(jsonPath("$.result.content[0].text", containsString("search_favorites")));
        String stranger = token(createUser());
        toolError(stranger, "remove_favorite", Map.of("favoriteId", favoriteId));
        assertEquals("Removed from favorites.", tool(token, "remove_favorite", Map.of("favoriteId", favoriteId)));
        assertTrue(JsonPath.<List<?>>read(tool(token, "search_favorites", Map.of()), "$.favorites").isEmpty());
    }

    @Test
    void historyMatchesPluralsAndSoundsAlikeSpellings() {
        List<String> words = ShoppingTools.words("Kirkland Signature Paper Towels");
        assertTrue(ShoppingTools.matchesAny("towel", words));
        assertTrue(ShoppingTools.matchesAny("towels", ShoppingTools.words("Paper towel")));
        assertTrue(ShoppingTools.matchesAny("towls", words));
        assertTrue(ShoppingTools.matchesAny("pap", words));
        assertFalse(ShoppingTools.matchesAny("milk", words));
        assertEquals("R163", ShoppingTools.soundex("Robert"));
    }

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private String tool(String token, String name, Map<String, Object> arguments) throws Exception {
        String response = rpc(token, "tools/call", Map.of("name", name, "arguments", arguments))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.result.content[0].text");
    }

    private ResultActions toolError(String token, String name, Map<String, Object> arguments) throws Exception {
        return rpc(token, "tools/call", Map.of("name", name, "arguments", arguments))
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    private ResultActions rpc(String token, String method, Map<String, Object> params) throws Exception {
        String body = new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        return mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body));
    }

    private String token(String user) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/users/me/access-tokens").with(httpBasic(user, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String createUser() throws Exception {
        String username = UUID.randomUUID().toString().substring(0, 30);
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        return username;
    }
}
