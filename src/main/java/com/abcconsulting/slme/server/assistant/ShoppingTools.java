package com.abcconsulting.slme.server.assistant;

import com.abcconsulting.slme.server.favorites.FavoriteService;
import com.abcconsulting.slme.server.model.Brand;
import com.abcconsulting.slme.server.model.Department;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.model.ShoppingListItem;
import com.abcconsulting.slme.server.photos.PhotoService;
import com.abcconsulting.slme.server.photos.ProductPhotoFinder;
import com.abcconsulting.slme.server.repository.ItemRepository;
import com.abcconsulting.slme.server.repository.ShoppingListItemDao;
import com.abcconsulting.slme.server.repository.ShoppingListRepository;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.stores.Store;
import com.abcconsulting.slme.server.stores.StoreCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What an AI assistant can do with a user's shopping lists (see {@link McpController}): find lists, read them,
 * search the items the user has added before, and add items, either copied from that history or new (optionally
 * with the product's photo from its web page). Failures are IllegalArgumentExceptions (or NoSuchElementExceptions)
 * whose message tells the assistant what to do instead.
 */
@Component
public class ShoppingTools {

    private static final Logger log = LoggerFactory.getLogger(ShoppingTools.class);
    private static final int MAX_LISTS = 50;
    private static final int MAX_HISTORY_RESULTS = 15;

    private final ShoppingListService shopping;
    private final ShoppingListRepository lists;
    private final ItemRepository items;
    private final ShoppingListItemDao listItems;
    private final PhotoService photos;
    private final ProductPhotoFinder photoFinder;
    private final StoreCatalog stores;
    private final FavoriteService favorites;

    public ShoppingTools(ShoppingListService shopping, ShoppingListRepository lists, ItemRepository items,
            ShoppingListItemDao listItems, PhotoService photos, ProductPhotoFinder photoFinder, StoreCatalog stores,
            FavoriteService favorites) {
        this.shopping = shopping;
        this.lists = lists;
        this.items = items;
        this.listItems = listItems;
        this.photos = photos;
        this.photoFinder = photoFinder;
        this.stores = stores;
        this.favorites = favorites;
    }

    public record ListSummary(long listId, String name, String store, boolean completed, OffsetDateTime createdAt,
            int itemCount, int itemsLeft) {
    }

    public record ListEntry(long itemId, String name, int quantity, String brand, String department, String comment,
            boolean checkedOff, boolean hasPhoto, OffsetDateTime addedAt) {
    }

    /** One thing the user has bought before; several list entries with the same name and brand are one record. */
    public record HistoryItem(long itemId, String name, String brand, String department, String comment,
            int lastQuantity, boolean hasPhoto, int timesAdded, OffsetDateTime lastAdded, List<String> stores) {
    }

    public record AddResult(String outcome, String message, long listId, ListEntry item, List<String> warnings) {
    }

    // ---- Lists ----

    /** The newest lists first; {@code search} matches a store ("costco") or words of the list's name. */
    public List<ListSummary> listShoppingLists(String username, String search, boolean includeCompleted) {
        String wanted = search == null ? "" : normalize(search);
        return shopping.getShoppingListsByUser(username, PageRequest.of(0, MAX_LISTS)).getContent().stream()
                .filter(list -> includeCompleted || !list.completed())
                .filter(list -> wanted.isEmpty() || matchesList(list, wanted))
                .map(list -> summary(list, username))
                .toList();
    }

    public ListSummary createShoppingList(String username, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give the list a name, e.g. \"Costco\".");
        }
        if (name.strip().length() > 255) {
            throw new IllegalArgumentException("The list name can be at most 255 characters.");
        }
        return summary(shopping.addShoppingList(name, username), username);
    }

    public List<ListEntry> listItems(String username, long listId) {
        ownList(listId, username);
        Map<Long, String> brands = brandNames();
        Map<Long, String> departments = departmentNames();
        return shopping.getShoppingItemsByList(listId, username).stream()
                .map(item -> entry(item, brands, departments))
                .toList();
    }

    public String removeItem(String username, long listId, long itemId) {
        ownList(listId, username);
        if (!shopping.deleteShoppingItemFromList(listId, itemId, username)) {
            throw new NoSuchElementException("That item isn't on this list. Read the list again for the item IDs.");
        }
        return "Removed.";
    }

    // ---- History ----

    /**
     * Items the user has added to lists before, most relevant first: those matching every word of the query, then
     * those added most often and most recently. Words match the start of a word in the name, brand or comment,
     * ignoring plurals, or sound alike.
     */
    public List<HistoryItem> searchHistory(String username, String query, String store) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Say what to look for, e.g. \"paper towels\".");
        }
        String storeKey = store == null || store.isBlank() ? null : storeKey(store);
        Map<Long, String> brands = brandNames();
        Map<Long, String> departments = departmentNames();
        Map<Long, List<String>> listNamesByItem = listItems.findListNamesByItem(username);

        record Scored(HistoryItem item, int score) {
        }
        List<Scored> results = new ArrayList<>();
        for (List<Item> group : history(username).values()) {
            Item latest = group.getFirst();
            String brand = brands.get(latest.brandId());
            Set<String> storeNames = group.stream()
                    .flatMap(item -> listNamesByItem.getOrDefault(item.itemId(), List.of()).stream())
                    .map(stores::match)
                    .flatMap(java.util.Optional::stream)
                    .filter(found -> storeKey == null || found.key().equals(storeKey))
                    .map(Store::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (storeKey != null && storeNames.isEmpty()) {
                continue;
            }
            List<String> searchable = words(latest.itemName() + " " + Objects.toString(brand, "") + " "
                    + Objects.toString(latest.itemComment(), ""));
            int score = (int) words.stream().filter(word -> matchesAny(word, searchable)).count();
            if (score == 0) {
                continue;
            }
            results.add(new Scored(new HistoryItem(latest.itemId(), latest.itemName(), brand,
                    departments.get(latest.departmentId()), latest.itemComment(),
                    latest.quantity() == null ? 1 : latest.quantity(), latest.photoId() != null, group.size(),
                    latest.createdAt(), List.copyOf(storeNames)), score));
        }
        return results.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(scored -> scored.item().timesAdded(), Comparator.reverseOrder())
                        .thenComparing(scored -> scored.item().lastAdded(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_HISTORY_RESULTS)
                .map(Scored::item)
                .toList();
    }

    /** The user's items grouped by name and brand, each group newest first. */
    private Map<String, List<Item>> history(String username) {
        return items.findAllByUsername(username).stream()
                .sorted(Comparator.comparing(Item::itemId).reversed())
                .collect(Collectors.groupingBy(item -> normalize(item.itemName()) + "|" + item.brandId(),
                        LinkedHashMap::new, Collectors.toList()));
    }

    // ---- Adding ----

    /** What to add: a past item to copy, or a new item described by name (and optionally its product page). */
    public record AddRequest(long listId, Long fromItemId, String name, String brand, String department,
            Integer quantity, String comment, String productUrl, boolean allowDuplicate) {
    }

    /**
     * Adds an item to a list, unless the list has it already (an entry that was checked off is unchecked instead).
     * A copied item keeps its brand, department, comment and photo; a new one takes the photo (and, when not given,
     * the name and brand) from its product page, and the department and photo of a past item with the same name.
     * Failing those, its photo is the best Open Food Facts match, as the clients suggest.
     */
    public AddResult addItem(String username, AddRequest request) {
        ownList(request.listId(), username);
        if (request.quantity() != null && (request.quantity() < 1 || request.quantity() > 999)) {
            throw new IllegalArgumentException("The quantity must be between 1 and 999.");
        }
        if (request.comment() != null && request.comment().length() > 1000) {
            throw new IllegalArgumentException("The comment can be at most 1000 characters.");
        }
        List<String> warnings = new ArrayList<>();
        Map<Long, String> brands = brandNames();
        Map<Long, String> departments = departmentNames();

        String name;
        Long brandId;
        Long departmentId;
        String comment;
        int quantity;
        Long photoId;
        if (request.fromItemId() != null) {
            Item past = items.findById(request.fromItemId())
                    .filter(item -> username.equals(item.username()))
                    .orElseThrow(() -> new NoSuchElementException(
                            "No item " + request.fromItemId() + " in the history. Search the history again for its ID."));
            name = past.itemName();
            brandId = request.brand() == null ? past.brandId() : brandId(request.brand(), brands);
            departmentId = request.department() == null ? past.departmentId() : departmentId(request.department(), departments);
            comment = request.comment() == null ? past.itemComment() : blankToNull(request.comment());
            quantity = request.quantity() == null ? (past.quantity() == null ? 1 : past.quantity()) : request.quantity();
            // Another brand is another product, so the past item's photo isn't it.
            photoId = Objects.equals(brandId, past.brandId()) ? past.photoId() : null;
            if (photoId == null && past.photoId() != null) {
                warnings.add("The past item's photo is of another brand, so it wasn't kept. Search the web for the "
                        + request.brand() + " product's page and give it to set_item_photo.");
            }
        } else {
            String productName = null;
            String productBrand = null;
            photoId = null;
            if (request.productUrl() != null && !request.productUrl().isBlank()) {
                try {
                    PhotoService.ImportedPhoto imported = photos.importFromWeb(username, request.productUrl().strip());
                    productName = imported.title();
                    productBrand = imported.brand();
                    String otherBrand = otherBrand(imported, request.brand());
                    if (otherBrand != null) {
                        warnings.add("The product page is for " + otherBrand + ", not " + request.brand()
                                + ", so its photo wasn't used. Find the " + request.brand() + " product's page and give it "
                                + "to set_item_photo, or check with the user which brand they want.");
                    } else {
                        photoId = imported.photoId();
                        if (!pageNamesBrand(imported, request.brand())) {
                            warnings.add("The product page (\"" + imported.title() + "\") doesn't name " + request.brand()
                                    + ". If it's another brand's product, find the " + request.brand()
                                    + " product's page and give it to set_item_photo.");
                        }
                    }
                } catch (RuntimeException failed) {
                    log.info("Couldn't import a photo from {}: {}", request.productUrl(), failed.getMessage());
                    warnings.add("No photo: " + failed.getMessage());
                }
            }
            name = request.name() != null && !request.name().isBlank() ? request.name().strip() : productName;
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Give the item a name"
                        + (request.productUrl() == null ? "." : "; its product page didn't have one."));
            }
            if (name.length() > 255) {
                throw new IllegalArgumentException("The name can be at most 255 characters.");
            }
            String brandName = request.brand() != null ? request.brand() : productBrand;
            brandId = brandName == null || brandName.isBlank() ? null : brandId(brandName, brands);
            Item previous = latestNamed(username, name);
            departmentId = request.department() != null ? departmentId(request.department(), departments)
                    : previous == null ? null : previous.departmentId();
            if (brandId == null && previous != null) {
                brandId = previous.brandId();
            }
            if (photoId == null && previous != null && Objects.equals(previous.brandId(), brandId)) {
                photoId = previous.photoId();
            }
            comment = blankToNull(request.comment());
            quantity = request.quantity() == null ? 1 : request.quantity();
        }

        if (!request.allowDuplicate()) {
            Long finalBrandId = brandId;
            ShoppingListItem existing = shopping.getShoppingItemsByList(request.listId(), username).stream()
                    .filter(item -> normalize(item.itemName()).equals(normalize(name)))
                    .filter(item -> item.brandId() == null || finalBrandId == null || item.brandId().equals(finalBrandId))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                brands = brandNames();
                if (existing.completed()) {
                    shopping.updateShoppingItemCompletion(request.listId(), existing.itemId(), username, false);
                    ShoppingListItem unchecked = shopping.getShoppingItemsByList(request.listId(), username).stream()
                            .filter(item -> item.itemId().equals(existing.itemId())).findFirst().orElse(existing);
                    return new AddResult("unchecked", "It was on the list but checked off; it's unchecked now.",
                            request.listId(), entry(unchecked, brands, departments), warnings);
                }
                if (existing.photoId() == null && photoId != null) {
                    photos.setItemPhoto(existing.itemId(), photoId, username);
                    ShoppingListItem withPhoto = shopping.getShoppingItemsByList(request.listId(), username).stream()
                            .filter(item -> item.itemId().equals(existing.itemId())).findFirst().orElse(existing);
                    return new AddResult("already_on_list", "It's already on the list; it has the product's photo now.",
                            request.listId(), entry(withPhoto, brands, departments), warnings);
                }
                return new AddResult("already_on_list", "It's already on the list, so nothing was added.",
                        request.listId(), entry(existing, brands, departments), warnings);
            }
        }

        if (photoId == null && request.fromItemId() == null) {
            photoId = productDatabasePhoto(username, name, brandNames().get(brandId)).orElse(null);
        }
        Item added = shopping.addShoppingItem(request.listId(), name, quantity, departmentId, brandId, comment, username);
        if (request.fromItemId() == null && (request.productUrl() == null || request.productUrl().isBlank())) {
            // Assistants tend to skip the web search; the tool's answer is the surest place to ask for it.
            String store = stores.match(ownList(request.listId(), username).shoppingListName())
                    .map(found -> found.domains().getFirst()).orElse(null);
            String product = (brandNames().getOrDefault(brandId, "") + " " + name).strip();
            warnings.add("No product page was given" + (photoId == null ? ", so the item has no photo" : "; its photo may "
                    + "not be the exact product") + ". Search the web for \"" + product
                    + (store == null ? "" : " " + store) + "\" and give the product page's "
                    + "link to set_item_photo (itemId " + added.itemId() + ").");
        }
        if (photoId != null) {
            try {
                photos.setItemPhoto(added.itemId(), photoId, username);
            } catch (RuntimeException failed) {
                log.warn("Couldn't attach photo {} to item {}", photoId, added.itemId(), failed);
                warnings.add("The photo couldn't be attached.");
            }
        }
        ShoppingListItem entry = shopping.getShoppingItemsByList(request.listId(), username).stream()
                .filter(item -> item.itemId().equals(added.itemId()))
                .findFirst()
                .orElseThrow();
        return new AddResult("added", "Added.", request.listId(), entry(entry, brandNames(), departments), warnings);
    }

    /** Adds a favorite to a list with its name, brand, department, notes and photo, unless the list has it already. */
    public AddResult addFavorite(String username, long listId, long favoriteId, Integer quantity) {
        ownList(listId, username);
        FavoriteService.AddedToList added;
        try {
            added = favorites.addToList(favoriteId, username, listId, quantity);
        } catch (NoSuchElementException missing) {
            throw new NoSuchElementException("No favorite " + favoriteId + ". Use search_favorites for the favorite IDs.");
        }
        ShoppingListItem item = shopping.getShoppingItemsByList(listId, username).stream()
                .filter(entry -> entry.itemId() == added.itemId())
                .findFirst()
                .orElseThrow();
        String message = switch (added.outcome()) {
            case "unchecked" -> "It was on the list but checked off; it's unchecked now.";
            case "already_on_list" -> "It's already on the list, so nothing was added.";
            default -> "Added.";
        };
        return new AddResult(added.outcome(), message, listId, entry(item, brandNames(), departmentNames()), List.of());
    }

    /** Gives an item on a list the photo from a product page (or image address), replacing any it had. */
    public ListEntry setItemPhoto(String username, long listId, long itemId, String productUrl) {
        ownList(listId, username);
        if (productUrl == null || productUrl.isBlank()) {
            throw new IllegalArgumentException("Give the product page's address.");
        }
        ShoppingListItem item = shopping.getShoppingItemsByList(listId, username).stream()
                .filter(entry -> entry.itemId() == itemId)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("That item isn't on this list. Read the list again for the item IDs."));
        PhotoService.ImportedPhoto imported = photos.importFromWeb(username, productUrl.strip());
        String brand = brandNames().get(item.brandId());
        String otherBrand = otherBrand(imported, brand);
        if (otherBrand != null) {
            throw new IllegalArgumentException("That page is for " + otherBrand + ", not " + brand
                    + ", so the photo wasn't changed. Find the " + brand + " product's page.");
        }
        photos.setItemPhoto(item.itemId(), imported.photoId(), username);
        return shopping.getShoppingItemsByList(listId, username).stream()
                .filter(entry -> entry.itemId() == itemId)
                .map(entry -> entry(entry, brandNames(), departmentNames()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The best Open Food Facts photo of this brand's product, stored for the user; empty without a brand, when none
     * of the results is the brand (the search falls back to any brand, and a wrong product's photo is worse than
     * none), or when it fails.
     */
    private java.util.Optional<Long> productDatabasePhoto(String username, String name, String brand) {
        if (brand == null || brand.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return photoFinder.find(name, brand).stream()
                    .filter(suggestion -> sameBrand(suggestion.brand(), brand))
                    .map(ProductPhotoFinder.Suggestion::imageUrl)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(address -> photos.importFromWeb(username, address).photoId());
        } catch (RuntimeException failed) {
            log.info("No product photo for {} ({}): {}", name, brand, failed.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * The brand a product page says it's for, when that isn't the item's brand; null when it is, or when the page
     * doesn't say. Assistants pick pages from search results without opening them, and a page of another brand gives
     * the item a photo of the wrong product.
     */
    static String otherBrand(PhotoService.ImportedPhoto page, String brand) {
        return brand == null || brand.isBlank() || page.brand() == null || sameBrand(page.brand(), brand)
                ? null : page.brand();
    }

    /** Whether a page that doesn't give its brand at least names the item's brand in the product's name. */
    static boolean pageNamesBrand(PhotoService.ImportedPhoto page, String brand) {
        if (brand == null || brand.isBlank() || page.brand() != null || page.title() == null) {
            return true;
        }
        List<String> brandWords = words(brand);
        String title = " " + normalize(page.title()) + " ";
        return brandWords.isEmpty() || title.contains(" " + String.join(" ", brandWords) + " ")
                || brandWords.getFirst().length() >= 4 && title.contains(" " + brandWords.getFirst() + " ");
    }

    /** "Kirkland Signature" and "Kirkland" are the same brand; a product may list several ("Charmin, P&G"). */
    static boolean sameBrand(String found, String wanted) {
        String expected = normalize(wanted);
        return found != null && !expected.isEmpty() && java.util.Arrays.stream(found.split("[,•]"))
                .map(ShoppingTools::normalize)
                .filter(candidate -> !candidate.isEmpty())
                .anyMatch(candidate -> candidate.startsWith(expected) || expected.startsWith(candidate));
    }

    /** The departments to file items under; they are shared by all users. */
    public List<String> departments() {
        return shopping.getDepartments().stream().map(Department::departmentName).sorted().toList();
    }

    // ---- Helpers ----

    ShoppingList ownList(long listId, String username) {
        return lists.findById(listId)
                .filter(list -> username.equals(list.username()))
                .orElseThrow(() -> new NoSuchElementException(
                        "No list " + listId + ". Use list_shopping_lists for the list IDs."));
    }

    private boolean matchesList(ShoppingList list, String wanted) {
        String storeName = stores.match(list.shoppingListName()).map(Store::name).orElse("");
        return normalize(list.shoppingListName() + " " + storeName).contains(wanted)
                || stores.match(wanted).flatMap(store -> stores.match(list.shoppingListName())
                        .filter(found -> found.key().equals(store.key()))).isPresent();
    }

    private ListSummary summary(ShoppingList list, String username) {
        List<ShoppingListItem> entries = shopping.getShoppingItemsByList(list.shoppingListId(), username);
        return new ListSummary(list.shoppingListId(), list.shoppingListName(),
                stores.match(list.shoppingListName()).map(Store::name).orElse(null), list.completed(),
                list.createdAt(), entries.size(), (int) entries.stream().filter(item -> !item.completed()).count());
    }

    private static ListEntry entry(ShoppingListItem item, Map<Long, String> brands, Map<Long, String> departments) {
        return new ListEntry(item.itemId(), item.itemName(), item.quantity() == null ? 1 : item.quantity(),
                brands.get(item.brandId()), departments.get(item.departmentId()), item.itemComment(),
                item.completed(), item.photoId() != null, item.createdAt());
    }

    private String storeKey(String store) {
        return stores.match(store)
                .or(() -> stores.find(normalize(store).replace(" ", "")))
                .map(Store::key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown store \"" + store + "\". HList knows "
                        + stores.all().stream().map(Store::name).collect(Collectors.joining(", ")) + "."));
    }

    private Item latestNamed(String username, String name) {
        String wanted = normalize(name);
        return items.findAllByUsername(username).stream()
                .filter(item -> normalize(item.itemName()).equals(wanted))
                .max(Comparator.comparing(Item::itemId))
                .orElse(null);
    }

    /** An existing brand with this name (ignoring case), or a new one. Brands are shared by all users. */
    Long brandId(String name, Map<Long, String> brands) {
        String wanted = name.strip();
        if (wanted.isEmpty()) {
            return null;
        }
        if (wanted.length() > 255) {
            throw new IllegalArgumentException("The brand can be at most 255 characters.");
        }
        return brands.entrySet().stream()
                .filter(brand -> normalize(brand.getValue()).equals(normalize(wanted)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> shopping.addBrand(wanted).brandId());
    }

    /** An existing department; they aren't created here, so the list of departments stays tidy. */
    Long departmentId(String name, Map<Long, String> departments) {
        if (name.isBlank()) {
            return null;
        }
        return departments.entrySet().stream()
                .filter(department -> normalize(department.getValue()).equals(normalize(name)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There's no department \"" + name.strip()
                        + "\". Use one of: " + String.join(", ", departments()) + " (or leave it out)."));
    }

    Map<Long, String> brandNames() {
        return shopping.getBrands().stream().collect(Collectors.toMap(Brand::brandId, Brand::brandName));
    }

    Map<Long, String> departmentNames() {
        return shopping.getDepartments().stream()
                .collect(Collectors.toMap(Department::departmentId, Department::departmentName));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static List<String> words(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }

    /** A query word matches the start of a word (ignoring a plural "s"/"es"), or a word that sounds alike. */
    static boolean matchesAny(String queryWord, List<String> words) {
        String wanted = singular(queryWord);
        String sound = queryWord.length() >= 4 ? soundex(queryWord) : null;
        return words.stream().anyMatch(word -> word.startsWith(wanted) || singular(word).equals(wanted)
                || (sound != null && word.length() >= 4 && soundex(word).equals(sound)));
    }

    private static String singular(String word) {
        if (word.length() > 4 && word.endsWith("es") && !word.endsWith("ses")) {
            return word.substring(0, word.length() - 2);
        }
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1) : word;
    }

    /** American Soundex ("Robert" → "R163"), as the clients use for sounds-alike matching. */
    static String soundex(String value) {
        String letters = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (letters.isEmpty()) {
            return "";
        }
        String codes = "01230120022455012623010202"; // A..Z
        StringBuilder result = new StringBuilder().append(letters.charAt(0));
        char previous = codes.charAt(letters.charAt(0) - 'A');
        for (int index = 1; index < letters.length() && result.length() < 4; index++) {
            char code = codes.charAt(letters.charAt(index) - 'A');
            if (code != '0' && code != previous) {
                result.append(code);
            }
            previous = code;
        }
        while (result.length() < 4) {
            result.append('0');
        }
        return result.toString();
    }
}
