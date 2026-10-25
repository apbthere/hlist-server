package com.abcconsulting.slme.server.assistant;

import com.abcconsulting.slme.server.favorites.Favorite;
import com.abcconsulting.slme.server.favorites.FavoriteService;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.photos.PhotoService;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.stores.StoreCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * What an AI assistant can do with the user's favorite products (see {@link McpController}): find them, save and
 * remove them, and record the prices it finds at the stores that sell them, so it (and the user) can add each
 * favorite to the list of the store with the best price.
 */
@Component
public class FavoriteTools {

    private static final Logger log = LoggerFactory.getLogger(FavoriteTools.class);
    private static final int MAX_RESULTS = 30;

    private final FavoriteService favorites;
    private final ShoppingTools shoppingTools;
    private final ShoppingListService shopping;
    private final PhotoService photos;
    private final StoreCatalog stores;

    public FavoriteTools(FavoriteService favorites, ShoppingTools shoppingTools, ShoppingListService shopping,
            PhotoService photos, StoreCatalog stores) {
        this.favorites = favorites;
        this.shoppingTools = shoppingTools;
        this.shopping = shopping;
        this.photos = photos;
        this.stores = stores;
    }

    /**
     * A store a favorite is sold at; openListId is the user's newest open list for that store (null if there is
     * none), where the favorite would be added.
     */
    public record StorePrice(String store, BigDecimal price, String size, String productUrl, OffsetDateTime priceCheckedAt,
            Long openListId, String openList) {
    }

    public record FavoriteResult(long favoriteId, String name, String brand, String category, String department,
            String comment, boolean hasPhoto, List<StorePrice> stores) {
    }

    public record SearchResult(List<String> categories, List<FavoriteResult> favorites) {
    }

    /** What to save: a new favorite (optionally copied from a past item) or changes to one. Null fields are kept. */
    public record SaveRequest(Long favoriteId, Long fromItemId, String name, String brand, String category,
            String department, String comment, List<String> stores, String productUrl) {
    }

    /**
     * The favorites matching every word of the query best (in their name, brand, category, notes or stores), or all
     * of them by category when there is no query.
     */
    public SearchResult search(String username, String query) {
        List<String> words = ShoppingTools.words(query);
        Map<Long, String> brands = shoppingTools.brandNames();
        Map<Long, String> departments = shoppingTools.departmentNames();
        Map<String, ShoppingList> openLists = openListsByStore(username);

        record Scored(Favorite favorite, int score) {
        }
        List<Scored> found = new ArrayList<>();
        for (Favorite favorite : favorites.list(username)) {
            List<String> searchable = ShoppingTools.words(String.join(" ", favorite.name(),
                    Objects.toString(brands.get(favorite.brandId()), ""), Objects.toString(favorite.category(), ""),
                    Objects.toString(favorite.comment(), ""),
                    String.join(" ", favorite.stores().stream().map(Favorite.StoreEntry::name).toList())));
            int score = (int) words.stream().filter(word -> ShoppingTools.matchesAny(word, searchable)).count();
            if (words.isEmpty() || score > 0) {
                found.add(new Scored(favorite, score));
            }
        }
        List<FavoriteResult> results = found.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(scored -> Objects.toString(scored.favorite().category(), "~"), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(scored -> scored.favorite().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_RESULTS)
                .map(scored -> result(scored.favorite(), brands, departments, openLists))
                .toList();
        return new SearchResult(favorites.categories(username), results);
    }

    public FavoriteResult save(String username, SaveRequest request) {
        Map<Long, String> departments = shoppingTools.departmentNames();
        Favorite base;
        if (request.favoriteId() != null) {
            base = own(request.favoriteId(), username);
        } else if (request.fromItemId() != null) {
            try {
                base = favorites.createFromItem(username, request.fromItemId(), request.category()).getKey();
            } catch (NoSuchElementException missing) {
                throw new NoSuchElementException("No item " + request.fromItemId()
                        + " in the history. Search the history again for its ID.");
            }
        } else {
            base = null;
        }

        String name = request.name() != null ? request.name().strip() : base == null ? null : base.name();
        Long brandId = base == null ? null : base.brandId();
        Long photoId = base == null ? null : base.photoId();
        if (request.brand() != null) {
            brandId = shoppingTools.brandId(request.brand(), shoppingTools.brandNames());
        }
        if (request.productUrl() != null) {
            PhotoService.ImportedPhoto imported = photos.importFromWeb(username, request.productUrl().strip());
            String brand = request.brand() != null ? request.brand() : shoppingTools.brandNames().get(brandId);
            String otherBrand = ShoppingTools.otherBrand(imported, brand);
            if (otherBrand != null) {
                throw new IllegalArgumentException("That page is for " + otherBrand + ", not " + brand
                        + ", so nothing was saved. Find the " + brand + " product's page.");
            }
            photoId = imported.photoId();
            if (name == null || name.isBlank()) {
                name = imported.title();
            }
            if (brandId == null && imported.brand() != null) {
                brandId = shoppingTools.brandId(imported.brand(), shoppingTools.brandNames());
            }
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Give the favorite a name, e.g. \"Coffee Beans\".");
        }
        Long departmentId = request.department() != null
                ? shoppingTools.departmentId(request.department(), departments)
                : base == null ? null : base.departmentId();
        String category = request.category() != null ? request.category() : base == null ? null : base.category();
        String comment = request.comment() != null ? request.comment() : base == null ? null : base.comment();
        // Stores it keeps keep their prices.
        List<FavoriteService.StoreInput> storeInputs = null;
        if (request.stores() != null) {
            storeInputs = new ArrayList<>();
            for (String store : request.stores()) {
                storeInputs.add((base == null ? List.<Favorite.StoreEntry>of() : base.stores()).stream()
                        .filter(known -> sameStore(known.name(), store))
                        .findFirst()
                        .map(known -> new FavoriteService.StoreInput(known.name(), known.price(), known.size(), known.productUrl()))
                        .orElse(new FavoriteService.StoreInput(store, null, null, null)));
            }
        }
        FavoriteService.FavoriteInput input = new FavoriteService.FavoriteInput(name, brandId, departmentId, category,
                comment, photoId, storeInputs);
        Favorite saved = base == null ? favorites.create(username, input) : favorites.update(base.favoriteId(), username, input);
        log.info("Saved favorite {} for {}", saved.favoriteId(), username);
        return result(saved, shoppingTools.brandNames(), departments, openListsByStore(username));
    }

    public String remove(String username, long favoriteId) {
        own(favoriteId, username);
        favorites.delete(favoriteId, username);
        return "Removed from favorites.";
    }

    /** Records the price seen at a store, or (available false) that the store doesn't sell it. */
    public FavoriteResult recordPrice(String username, long favoriteId, String store, BigDecimal price, String size,
            String productUrl, boolean available) {
        own(favoriteId, username);
        if (store == null || store.isBlank()) {
            throw new IllegalArgumentException("Say which store the price is from, e.g. \"Costco\".");
        }
        Favorite saved;
        if (!available) {
            saved = favorites.removeStore(favoriteId, username, store);
        } else {
            if (price == null) {
                throw new IllegalArgumentException("Give the price, e.g. 12.99, or available false if the store doesn't sell it.");
            }
            saved = favorites.recordPrice(favoriteId, username,
                    new FavoriteService.StoreInput(store, price, size, productUrl));
        }
        return result(saved, shoppingTools.brandNames(), shoppingTools.departmentNames(), openListsByStore(username));
    }

    // ---- Helpers ----

    private Favorite own(long favoriteId, String username) {
        try {
            return favorites.get(favoriteId, username);
        } catch (NoSuchElementException missing) {
            throw new NoSuchElementException("No favorite " + favoriteId + ". Use search_favorites for the favorite IDs.");
        }
    }

    private boolean sameStore(String known, String given) {
        return known.equalsIgnoreCase(given.strip())
                || stores.match(given).map(store -> store.name().equals(known)).orElse(false);
    }

    /** The newest open list of each store HList knows, by the store's name. */
    private Map<String, ShoppingList> openListsByStore(String username) {
        Map<String, ShoppingList> open = new LinkedHashMap<>();
        shopping.getShoppingListsByUser(username, PageRequest.of(0, 50)).getContent().stream()
                .filter(list -> !list.completed())
                .forEach(list -> stores.match(list.shoppingListName())
                        .ifPresent(store -> open.putIfAbsent(store.name(), list)));
        return open;
    }

    private static FavoriteResult result(Favorite favorite, Map<Long, String> brands, Map<Long, String> departments,
            Map<String, ShoppingList> openLists) {
        return new FavoriteResult(favorite.favoriteId(), favorite.name(), brands.get(favorite.brandId()),
                favorite.category(), departments.get(favorite.departmentId()), favorite.comment(),
                favorite.photoId() != null,
                favorite.stores().stream().map(store -> {
                    ShoppingList list = openLists.get(store.name());
                    return new StorePrice(store.name(), store.price(), store.size(), store.productUrl(),
                            store.priceCheckedAt(), list == null ? null : list.shoppingListId(),
                            list == null ? null : list.shoppingListName());
                }).toList());
    }
}
