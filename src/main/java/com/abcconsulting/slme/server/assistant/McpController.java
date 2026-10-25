package com.abcconsulting.slme.server.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A Model Context Protocol server at /mcp (Streamable HTTP transport, plain JSON responses, no sessions), so AI
 * assistants such as Claude can read and add to the signed-in user's shopping lists. Assistants sign in with an
 * access token ({@code Authorization: Bearer hlist_...}, see {@link AccessTokenFilter}).
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    static final String LATEST_PROTOCOL = "2025-06-18";
    private static final Set<String> PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private static final String INSTRUCTIONS = """
            HList is the user's shopping list app. Lists are usually named after a store ("Costco", "Publix run").
            To add something to a list:
            1. Find the list with list_shopping_lists (search "costco" finds Costco lists). Use the newest open one
               unless the user names another; if there is none, ask before creating one. Never add to a list
               that's marked done (a past trip) unless the user asks for that list.
            2. Read it with get_list_items. If the item is already there, say so instead of adding it again.
            3. Look for it in search_item_history (with store "costco" for Costco items). If a past item matches what
               the user asked for, add it with add_item_to_list and fromItemId, which keeps its brand, department
               and photo. If several could match and it isn't clear which one the user means, ask.
            4. Otherwise search the web for the product at that store (e.g. "Bounty paper towels costco.com") and
               add it with add_item_to_list giving its name, brand and productUrl: the product page's link from the
               search results. Use the store's own site (costco.com, target.com, ...), not resellers or price
               trackers. The page must be for that brand's product: its title in the results should name the brand.
               You don't need to open the page (store sites often block assistants); HList reads it and takes the
               product photo from it. If the user didn't name a brand and several fit, prefer the store's own brand
               (Kirkland Signature at Costco) or ask. Keep names short, the way they appear on a shelf label
               ("Paper Towels", brand "Kirkland Signature"), without sizes or counts in the name; put a size or
               count the user cares about in the comment. Pick its department from list_departments. Without a
               product page, HList finds a product photo itself.
            To change an item's brand, remove it and add the other brand's product the same way, with a new search
            for that brand's page. Read the warnings add_item_to_list returns, and act on them before you answer.
            Tell the user what you added, or what was already there.

            Favorites are products the user buys again and again, kept apart from lists and grouped by category
            ("Coffee", "Sake"). Each lists the stores it's sold at, with the price last seen there and the
            user's newest open list for that store (openListId).
            - When the user asks for something they may keep as a favorite ("my coffee", "the usual sake"), look in
              search_favorites first, then in search_item_history.
            - To add a favorite to a list, unless the user named the store, first find which store has the best
              price. For each of its stores (or, if it has none, the stores the user has lists for), search the web
              for the product at that store's site (e.g. "Lavazza Super Crema costco.com") and read the price and
              size from the results or the page. When sizes differ, compare the price per unit (per oz, per count).
              Record every price you found with record_store_price, so HList remembers it, and available false for
              a store that doesn't sell it. Don't guess a price you didn't find; a price recorded earlier
              (priceCheckedAt) is only a fallback, and say how old it is.
            - Then add it with add_item_to_list and fromFavoriteId to the cheapest store's open list. If that store
              has no open list, ask before creating one. Tell the user the prices you compared, where each came
              from (online prices can differ from the shelf), and where you added it.
            - To make something a favorite, use save_favorite (fromItemId copies a past item) with a category the
              user already has (search_favorites lists them) when one fits.""";

    private final ShoppingTools tools;
    private final FavoriteTools favoriteTools;
    private final JsonMapper json;
    private final String version;

    public McpController(ShoppingTools tools, FavoriteTools favoriteTools, JsonMapper json,
            @Value("${hlist.build.server-commit:dev}") String serverCommit) {
        this.tools = tools;
        this.favoriteTools = favoriteTools;
        this.json = json;
        this.version = serverCommit;
    }

    /** No server-to-client stream: everything is a reply to a POST. Clients that try to open one get 405, as the spec asks. */
    @GetMapping(AccessTokenFilter.MCP_PATH)
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST).build();
    }

    @PostMapping(path = AccessTokenFilter.MCP_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(Authentication authentication, @RequestBody JsonNode message) {
        if (!message.isObject() || !message.path("method").isString()) {
            // Responses from the client (to requests this server never sends) and batches aren't used.
            return message.isObject() && !message.has("method")
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.badRequest().body(error(null, -32600, "Expected a JSON-RPC request"));
        }
        String method = message.get("method").asString();
        JsonNode id = message.get("id");
        if (!"tools/call".equals(method)) {
            log.info("MCP {} {}{}", authentication.getName(), method,
                    "initialize".equals(method) ? " from " + message.path("params").path("clientInfo") : "");
        }
        if (id == null) {
            return ResponseEntity.accepted().build(); // a notification, e.g. notifications/initialized
        }
        JsonNode params = message.path("params");
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", toolDefinitions()));
            case "tools/call" -> result(id, call(authentication.getName(), params));
            default -> error(id, -32601, "Unknown method " + method);
        });
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asString("");
        return Map.of(
                "protocolVersion", PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "hlist", "title", "HList shopping lists", "version", version),
                "instructions", INSTRUCTIONS);
    }

    private Map<String, Object> call(String username, JsonNode params) {
        String name = params.path("name").asString("");
        JsonNode arguments = params.path("arguments");
        try {
            Object result = switch (name) {
                case "list_shopping_lists" -> tools.listShoppingLists(username,
                        text(arguments, "search"), Boolean.TRUE.equals(bool(arguments, "includeCompleted")));
                case "create_shopping_list" -> tools.createShoppingList(username, text(arguments, "name"));
                case "get_list_items" -> tools.listItems(username, required(arguments, "listId"));
                case "search_item_history" -> tools.searchHistory(username,
                        text(arguments, "query"), text(arguments, "store"));
                case "add_item_to_list" -> number(arguments, "fromFavoriteId") != null
                        ? tools.addFavorite(username, required(arguments, "listId"),
                                required(arguments, "fromFavoriteId"), integer(arguments, "quantity"),
                                Boolean.TRUE.equals(bool(arguments, "reopenList")))
                        : tools.addItem(username, new ShoppingTools.AddRequest(
                        required(arguments, "listId"), number(arguments, "fromItemId"), text(arguments, "name"),
                        text(arguments, "brand"), text(arguments, "department"),
                        integer(arguments, "quantity"), text(arguments, "comment"), text(arguments, "productUrl"),
                        Boolean.TRUE.equals(bool(arguments, "allowDuplicate")),
                        Boolean.TRUE.equals(bool(arguments, "reopenList"))));
                case "remove_item_from_list" -> tools.removeItem(username,
                        required(arguments, "listId"), required(arguments, "itemId"));
                case "set_item_photo" -> tools.setItemPhoto(username, required(arguments, "listId"),
                        required(arguments, "itemId"), text(arguments, "productUrl"));
                case "list_departments" -> tools.departments();
                case "search_favorites" -> favoriteTools.search(username, text(arguments, "query"));
                case "save_favorite" -> favoriteTools.save(username, new FavoriteTools.SaveRequest(
                        number(arguments, "favoriteId"), number(arguments, "fromItemId"), text(arguments, "name"),
                        text(arguments, "brand"), text(arguments, "category"), text(arguments, "department"),
                        text(arguments, "comment"), texts(arguments, "stores"), text(arguments, "productUrl")));
                case "remove_favorite" -> favoriteTools.remove(username, required(arguments, "favoriteId"));
                case "record_store_price" -> favoriteTools.recordPrice(username, required(arguments, "favoriteId"),
                        text(arguments, "store"), decimal(arguments, "price"), text(arguments, "size"),
                        text(arguments, "productUrl"), !Boolean.FALSE.equals(bool(arguments, "available")));
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
            String answer = result instanceof String message ? message : json.writeValueAsString(result);
            log.info("MCP {} {} {} -> {}", username, name, arguments, abbreviate(answer));
            return Map.of("content", List.of(text(answer)), "isError", false);
        } catch (IllegalArgumentException | NoSuchElementException refused) {
            log.info("MCP {} {} {} refused: {}", username, name, arguments, refused.getMessage());
            return Map.of("content", List.of(text(refused.getMessage())), "isError", true);
        } catch (RuntimeException failed) {
            log.warn("MCP tool {} failed", name, failed);
            return Map.of("content", List.of(text("Something went wrong on the HList server. Try again.")), "isError", true);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private static List<Map<String, Object>> toolDefinitions() {
        Map<String, Object> listId = Map.of("type", "integer", "description", "The list's ID, from list_shopping_lists.");
        return List.of(
                tool("list_shopping_lists",
                        "The user's open shopping lists, newest first, with their store and how many items are left.",
                        Map.of(
                                "search", Map.of("type", "string",
                                        "description", "A store (\"costco\") or words from the list's name."),
                                "includeCompleted", Map.of("type", "boolean",
                                        "description", "Also lists marked done (past trips), for reading only; "
                                                + "items aren't added to them. Default false.")),
                        List.of(), true),
                tool("create_shopping_list",
                        "Creates a shopping list. Name it after the store (\"Costco\") so HList shows its icon.",
                        Map.of("name", Map.of("type", "string", "description", "The list's name.")),
                        List.of("name"), false),
                tool("get_list_items",
                        "The items on a list: name, quantity, brand, department, comment, and whether it's checked off.",
                        Map.of("listId", listId), List.of("listId"), true),
                tool("search_item_history",
                        "Items the user has added to lists before, best matches first (matching every word, then "
                                + "added most often and most recently), with the stores they were bought at. "
                                + "Matches word starts, plurals and sounds-alike spellings; try a shorter or "
                                + "different word if nothing fits.",
                        Map.of(
                                "query", Map.of("type", "string", "description", "What to look for, e.g. \"paper towels\"."),
                                "store", Map.of("type", "string",
                                        "description", "Only items bought at this store, e.g. \"costco\".")),
                        List.of("query"), true),
                tool("add_item_to_list",
                        "Adds an item to a list, unless it's on the list already (if it was checked off, it's "
                                + "unchecked instead). Either copy a past item with fromItemId (from "
                                + "search_item_history; keeps its brand, department, comment and photo), or "
                                + "describe a new one with name, brand and productUrl. For a new item, first search "
                                + "the web for the product at the list's store (e.g. \"Kirkland toilet paper "
                                + "costco.com\") and pass the product page's link from the results as productUrl; "
                                + "you don't need to open it. HList takes the photo from it. To add a favorite, "
                                + "give fromFavoriteId instead.",
                        addItemProperties(listId),
                        List.of("listId"), false),
                tool("remove_item_from_list", "Removes an item from a list.",
                        Map.of("listId", listId,
                                "itemId", Map.of("type", "integer", "description", "The item's ID, from get_list_items.")),
                        List.of("listId", "itemId"), false),
                tool("set_item_photo",
                        "Gives an item on a list the photo from its product page (e.g. a costco.com product page) "
                                + "or an image address, replacing any photo it had.",
                        Map.of("listId", listId,
                                "itemId", Map.of("type", "integer", "description", "The item's ID, from get_list_items."),
                                "productUrl", Map.of("type", "string", "description", "The product page or image address.")),
                        List.of("listId", "itemId", "productUrl"), false),
                tool("list_departments", "The departments items can be filed under.", Map.of(), List.of(), true),
                tool("search_favorites",
                        "The user's favorite products and their categories. Each favorite lists the stores it's sold "
                                + "at, cheapest known price first, with when that price was seen and the user's "
                                + "newest open list for the store (openListId). Without a query, all favorites.",
                        Map.of("query", Map.of("type", "string",
                                "description", "Words from the name, brand, category or store, e.g. \"coffee\".")),
                        List.of(), true),
                tool("save_favorite",
                        "Makes a product a favorite, or changes one (give favoriteId; fields left out are kept). "
                                + "fromItemId copies a past item from search_item_history with its stores.",
                        favoriteProperties(), List.of(), false),
                tool("remove_favorite", "Removes a product from the user's favorites (not from any list).",
                        Map.of("favoriteId", Map.of("type", "integer", "description", "From search_favorites.")),
                        List.of("favoriteId"), false),
                tool("record_store_price",
                        "Records the price of a favorite at a store, as you found it just now (adds the store to "
                                + "the favorite if it wasn't listed), or with available false that the store "
                                + "doesn't sell it.",
                        Map.of(
                                "favoriteId", Map.of("type", "integer", "description", "From search_favorites."),
                                "store", Map.of("type", "string", "description", "The store, e.g. \"Costco\"."),
                                "price", Map.of("type", "number", "description", "The price in dollars, e.g. 12.99."),
                                "size", Map.of("type", "string",
                                        "description", "What the price is for, e.g. \"2.2 lb bag\" or \"6 pack\"."),
                                "productUrl", Map.of("type", "string",
                                        "description", "The product's page at that store, where the price is."),
                                "available", Map.of("type", "boolean",
                                        "description", "False when the store doesn't sell it. Default true.")),
                        List.of("favoriteId", "store"), false));
    }

    private static Map<String, Object> addItemProperties(Map<String, Object> listId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("listId", listId);
        properties.put("fromItemId", Map.of("type", "integer",
                "description", "A past item to add again, from search_item_history."));
        properties.put("fromFavoriteId", Map.of("type", "integer",
                "description", "A favorite to add, from search_favorites (keeps its brand, "
                + "department, notes and photo)."));
        properties.put("name", Map.of("type", "string",
                "description", "A new item's name, short like a shelf label, without the brand. "
                + "Taken from the product page when left out."));
        properties.put("brand", Map.of("type", "string",
                "description", "The brand, e.g. \"Kirkland Signature\"."));
        properties.put("department", Map.of("type", "string",
                "description", "The best fit from list_departments (paper towels: household or paper goods); "
                + "leave it out only when none fits. Not needed with fromItemId."));
        properties.put("quantity", Map.of("type", "integer", "minimum", 1, "description", "Default 1."));
        properties.put("comment", Map.of("type", "string", "description", "A note, e.g. a size or flavor."));
        properties.put("productUrl", Map.of("type", "string",
                "description", "The product's web page (or image) address, for its photo."));
        properties.put("allowDuplicate", Map.of("type", "boolean",
                "description", "Add it even if the list already has an item with this name."));
        properties.put("reopenList", Map.of("type", "boolean",
                "description", "Add to a list marked done, reopening it. Only when the user "
                + "asked for that list."));
        return properties;
    }

    private static Map<String, Object> favoriteProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("favoriteId", Map.of("type", "integer", "description", "The favorite to change."));
        properties.put("fromItemId", Map.of("type", "integer",
                "description", "A past item to make a favorite, from search_item_history."));
        properties.put("name", Map.of("type", "string", "description", "Short like a shelf label, without the brand."));
        properties.put("brand", Map.of("type", "string", "description", "The brand."));
        properties.put("category", Map.of("type", "string",
                "description", "The user's grouping, e.g. \"Coffee\" or \"Sake\"; reuse one they have."));
        properties.put("department", Map.of("type", "string",
                "description", "From list_departments, for when it's added to a list."));
        properties.put("comment", Map.of("type", "string", "description", "A note, e.g. the roast or size the user likes."));
        properties.put("stores", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Every store it's sold at, e.g. [\"Costco\", \"Publix\"]; replaces the list (prices "
                        + "of stores kept stay)."));
        properties.put("productUrl", Map.of("type", "string", "description", "A product page, for its photo."));
        return properties;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
            List<String> required, boolean readOnly) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.of("name", name, "description", description, "inputSchema", schema,
                "annotations", Map.of("readOnlyHint", readOnly, "destructiveHint", name.startsWith("remove"),
                        "openWorldHint", name.equals("add_item_to_list") || name.equals("set_item_photo")
                                || name.equals("save_favorite")));
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private static String text(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException(name + " must be text.");
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static Long number(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isString() && value.asString().strip().matches("\\d{1,18}")) {
            return Long.parseLong(value.asString().strip());
        }
        throw new IllegalArgumentException(name + " must be a whole number.");
    }

    private static long required(JsonNode arguments, String name) {
        Long value = number(arguments, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }

    private static Integer integer(JsonNode arguments, String name) {
        Long value = number(arguments, name);
        if (value != null && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(name + " is too large.");
        }
        return value == null ? null : value.intValue();
    }

    /** A price: a number, or text such as "$12.99". */
    private static BigDecimal decimal(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isString() && value.asString().strip().matches("\\$?\\s*\\d{1,8}(\\.\\d{1,2})?")) {
            return new BigDecimal(value.asString().replaceAll("[$\\s]", ""));
        }
        throw new IllegalArgumentException(name + " must be a number, e.g. 12.99.");
    }

    private static List<String> texts(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(name + " must be a list of names.");
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isString() || element.asString().isBlank()) {
                throw new IllegalArgumentException(name + " must be a list of names.");
            }
            texts.add(element.asString().strip());
        }
        return texts;
    }

    private static Boolean bool(JsonNode arguments, String name) {
        JsonNode value = arguments.path(name);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.isString() ? Boolean.valueOf(value.asString()) : null;
    }
}
