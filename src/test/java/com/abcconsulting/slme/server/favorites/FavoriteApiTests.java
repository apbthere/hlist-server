package com.abcconsulting.slme.server.favorites;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
class FavoriteApiTests {

    private static final String PASSWORD = "favorite-password";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void favoritesAreKeptEditedAndRemovedByTheirOwnerOnly() throws Exception {
        String owner = createUser();
        String other = createUser();

        String created = mockMvc.perform(json(post("/api/favorites"), owner, """
                {"name": " Super Crema ", "category": "coffee",
                 "stores": [{"name": "costco", "price": 21.99, "size": "2.2 lb"}, {"name": "total wine"}, {"name": "Costco"}]}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Super Crema"))
                .andExpect(jsonPath("$.category").value("Coffee"))
                .andExpect(jsonPath("$.stores", hasSize(2)))
                .andExpect(jsonPath("$.stores[0].name").value("Costco"))
                .andExpect(jsonPath("$.stores[0].key").value("costco"))
                .andExpect(jsonPath("$.stores[0].price").value(21.99))
                .andExpect(jsonPath("$.stores[1].name").value("Total wine"))
                .andExpect(jsonPath("$.stores[1].key").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long favoriteId = id(created, "$.favoriteId");
        String checkedAt = JsonPath.read(created, "$.stores[0].priceCheckedAt");

        // The same category, typed differently, is the same category; an unchanged price keeps when it was seen.
        mockMvc.perform(json(post("/api/favorites"), owner, "{\"name\":\"Espresso Beans\",\"category\":\"COFFEE\"}"))
                .andExpect(jsonPath("$.category").value("Coffee"));
        mockMvc.perform(json(put("/api/favorites/" + favoriteId), owner, """
                {"name": "Super Crema Beans", "category": "Coffee",
                 "stores": [{"name": "Costco", "price": 21.99, "size": "2.2 lb"}, {"name": "Publix", "price": 16.49}]}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Super Crema Beans"))
                .andExpect(jsonPath("$.stores[0].name").value("Publix"))
                .andExpect(jsonPath("$.stores[1].priceCheckedAt").value(checkedAt));
        mockMvc.perform(json(put("/api/favorites/" + favoriteId), owner, "{\"name\":\"Kept stores\"}"))
                .andExpect(jsonPath("$.stores", hasSize(2)));
        mockMvc.perform(json(put("/api/favorites/" + favoriteId), owner,
                "{\"name\":\"Bad\",\"stores\":[{\"name\":\"Costco\",\"price\":-1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The price at Costco isn't a price."));

        mockMvc.perform(json(put("/api/favorites/" + favoriteId), other, "{\"name\":\"Stolen\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/favorites/" + favoriteId).with(httpBasic(other, PASSWORD))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/favorites").with(httpBasic(other, PASSWORD))).andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(delete("/api/favorites/" + favoriteId).with(httpBasic(owner, PASSWORD))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/favorites").with(httpBasic(owner, PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Espresso Beans"));
    }

    @Test
    void itemsOnAListBecomeFavoritesSoldAtTheListsStore() throws Exception {
        String owner = createUser();
        long listId = id(mockMvc.perform(json(post("/api/shopping-lists"), owner, "{\"name\":\"Costco run\"}"))
                .andReturn().getResponse().getContentAsString(), "$.shoppingListId");
        long brandId = id(mockMvc.perform(json(post("/api/brands"), owner, "{\"name\":\"Brand-" + owner + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.brandId");
        long itemId = id(mockMvc.perform(json(post("/api/shopping-lists/" + listId + "/items"), owner,
                        "{\"name\":\"Junmai Sake\",\"brandId\":" + brandId + ",\"comment\":\"Gekkeikan\"}"))
                .andReturn().getResponse().getContentAsString(), "$.itemId");
        long photoId = id(mockMvc.perform(post("/api/photos").with(httpBasic(owner, PASSWORD))
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg()))
                .andReturn().getResponse().getContentAsString(), "$.photoId");
        mockMvc.perform(json(put("/api/items/" + itemId + "/photo"), owner, "{\"photoId\":" + photoId + "}"))
                .andExpect(status().isNoContent());

        String favorite = mockMvc.perform(json(post("/api/favorites/from-item"), owner, "{\"itemId\":" + itemId + ",\"category\":\"sake\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Junmai Sake"))
                .andExpect(jsonPath("$.brandId").value(brandId))
                .andExpect(jsonPath("$.comment").value("Gekkeikan"))
                .andExpect(jsonPath("$.category").value("Sake"))
                .andExpect(jsonPath("$.photoId").value(photoId))
                .andExpect(jsonPath("$.stores[0].name").value("Costco"))
                .andReturn().getResponse().getContentAsString();
        long favoriteId = id(favorite, "$.favoriteId");
        mockMvc.perform(json(post("/api/favorites/from-item"), owner, "{\"itemId\":" + itemId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favoriteId").value(favoriteId));

        // The brand is in use by the favorite now too.
        mockMvc.perform(delete("/api/brands/" + brandId).with(httpBasic(owner, PASSWORD))).andExpect(status().isConflict());

        // Removing the item from the list (and its photo from the item) leaves the favorite's photo.
        mockMvc.perform(delete("/api/items/" + itemId + "/photo").with(httpBasic(owner, PASSWORD))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/photos/" + photoId).with(httpBasic(owner, PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void addingAFavoriteToAListNeverDuplicatesIt() throws Exception {
        String owner = createUser();
        String other = createUser();
        long listId = id(mockMvc.perform(json(post("/api/shopping-lists"), owner, "{\"name\":\"Publix\"}"))
                .andReturn().getResponse().getContentAsString(), "$.shoppingListId");
        long photoId = id(mockMvc.perform(post("/api/photos").with(httpBasic(owner, PASSWORD))
                        .contentType(MediaType.IMAGE_JPEG).content(jpeg()))
                .andReturn().getResponse().getContentAsString(), "$.photoId");
        long favoriteId = id(mockMvc.perform(json(post("/api/favorites"), owner,
                        "{\"name\":\"Cold Brew\",\"comment\":\"Black\",\"photoId\":" + photoId + "}"))
                .andReturn().getResponse().getContentAsString(), "$.favoriteId");

        String added = mockMvc.perform(json(post("/api/favorites/" + favoriteId + "/add-to-list"), owner,
                "{\"listId\":" + listId + ",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("added"))
                .andReturn().getResponse().getContentAsString();
        long itemId = id(added, "$.itemId");
        mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemName").value("Cold Brew"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].itemComment").value("Black"))
                .andExpect(jsonPath("$[0].photoId").value(photoId));

        mockMvc.perform(json(post("/api/favorites/" + favoriteId + "/add-to-list"), owner, "{\"listId\":" + listId + "}"))
                .andExpect(jsonPath("$.outcome").value("already_on_list"))
                .andExpect(jsonPath("$.itemId").value(itemId));
        mockMvc.perform(json(patch("/api/shopping-lists/" + listId + "/items/" + itemId + "/completion"), owner, "{\"completed\":true}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(json(post("/api/favorites/" + favoriteId + "/add-to-list"), owner, "{\"listId\":" + listId + "}"))
                .andExpect(jsonPath("$.outcome").value("unchecked"));
        mockMvc.perform(get("/api/shopping-lists/" + listId + "/items").with(httpBasic(owner, PASSWORD)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].completed").value(false));

        mockMvc.perform(json(post("/api/favorites/" + favoriteId + "/add-to-list"), other, "{\"listId\":" + listId + "}"))
                .andExpect(status().isNotFound());
        long otherList = id(mockMvc.perform(json(post("/api/shopping-lists"), other, "{\"name\":\"Mine\"}"))
                .andReturn().getResponse().getContentAsString(), "$.shoppingListId");
        mockMvc.perform(json(post("/api/favorites/" + favoriteId + "/add-to-list"), owner, "{\"listId\":" + otherList + "}"))
                .andExpect(status().isNotFound());
        assertEquals(List.of(), JsonPath.read(mockMvc.perform(get("/api/shopping-lists/" + otherList + "/items")
                .with(httpBasic(other, PASSWORD))).andReturn().getResponse().getContentAsString(), "$"));
    }

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
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
