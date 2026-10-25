package com.abcconsulting.slme.server.favorites;

import com.abcconsulting.slme.server.live.DataChangedEvent;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingListItem;
import com.abcconsulting.slme.server.photos.PhotoRepository;
import com.abcconsulting.slme.server.photos.PhotoService;
import com.abcconsulting.slme.server.repository.BrandRepository;
import com.abcconsulting.slme.server.repository.DepartmentRepository;
import com.abcconsulting.slme.server.repository.ItemRepository;
import com.abcconsulting.slme.server.repository.ShoppingListItemDao;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.stores.Store;
import com.abcconsulting.slme.server.stores.StoreCatalog;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Favorite products: kept, edited and removed by hand, made from an item on a list, and added to lists. Each store
 * a favorite is sold at can carry the price last seen there, so the user (or an assistant) can pick the cheapest.
 * Invalid input is an IllegalArgumentException whose message is shown to the user as is.
 */
@Service
public class FavoriteService {

    static final int MAX_STORES = 20;

    /** What a favorite should be. Null stores keeps the ones it has. */
    public record FavoriteInput(String name, Long brandId, Long departmentId, String category, String comment,
            Long photoId, List<StoreInput> stores) {
    }

    /** A store the favorite is sold at, with the price seen there (or null). */
    public record StoreInput(String name, BigDecimal price, String size, String productUrl) {
    }

    /**
     * @param outcome "added", "unchecked" (it was on the list, checked off) or "already_on_list"
     */
    public record AddedToList(String outcome, long listId, long itemId) {
    }

    private final FavoriteRepository favorites;
    private final ItemRepository items;
    private final ShoppingListItemDao listItems;
    private final ShoppingListService shopping;
    private final BrandRepository brands;
    private final DepartmentRepository departments;
    private final PhotoService photoService;
    private final PhotoRepository photos;
    private final StoreCatalog stores;
    private final ApplicationEventPublisher events;

    public FavoriteService(FavoriteRepository favorites, ItemRepository items, ShoppingListItemDao listItems,
            ShoppingListService shopping, BrandRepository brands, DepartmentRepository departments,
            PhotoService photoService, PhotoRepository photos, StoreCatalog stores, ApplicationEventPublisher events) {
        this.favorites = favorites;
        this.items = items;
        this.listItems = listItems;
        this.shopping = shopping;
        this.brands = brands;
        this.departments = departments;
        this.photoService = photoService;
        this.photos = photos;
        this.stores = stores;
        this.events = events;
    }

    public List<Favorite> list(String username) {
        return favorites.findAll(username, this::storeKey);
    }

    public Favorite get(long favoriteId, String username) {
        return favorites.find(favoriteId, username, this::storeKey)
                .orElseThrow(() -> new NoSuchElementException("Favorite not found"));
    }

    /** The user's categories, most used first. */
    public List<String> categories(String username) {
        return favorites.categories(username);
    }

    /** The favorite for this product (same name, ignoring case and spacing, and same brand), if there is one. */
    public Optional<Favorite> findFor(String username, String name, Long brandId) {
        String wanted = normalize(name);
        return list(username).stream()
                .filter(favorite -> normalize(favorite.name()).equals(wanted) && Objects.equals(favorite.brandId(), brandId))
                .findFirst();
    }

    @Transactional
    public Favorite create(String username, FavoriteInput input) {
        FavoriteRepository.Fields fields = fields(username, input);
        long favoriteId = favorites.insert(username, fields);
        favorites.replaceStores(favoriteId, storeRows(input.stores(), List.of()));
        events.publishEvent(DataChangedEvent.favorites(username));
        return get(favoriteId, username);
    }

    @Transactional
    public Favorite update(long favoriteId, String username, FavoriteInput input) {
        Favorite existing = get(favoriteId, username);
        FavoriteRepository.Fields fields = fields(username, input);
        favorites.update(favoriteId, username, fields);
        if (input.stores() != null) {
            favorites.replaceStores(favoriteId, storeRows(input.stores(), existing.stores()));
            favorites.touch(favoriteId);
        }
        if (existing.photoId() != null && !existing.photoId().equals(fields.photoId())) {
            photos.deleteIfUnused(existing.photoId());
        }
        events.publishEvent(DataChangedEvent.favorites(username));
        return get(favoriteId, username);
    }

    @Transactional
    public void delete(long favoriteId, String username) {
        Favorite existing = get(favoriteId, username);
        favorites.delete(favoriteId, username);
        if (existing.photoId() != null) {
            photos.deleteIfUnused(existing.photoId());
        }
        events.publishEvent(DataChangedEvent.favorites(username));
    }

    /**
     * Makes one of the user's items a favorite: its name, brand, department, notes and photo, sold at the stores of
     * the lists it (or an item with the same name and brand) has been on. An item that already is a favorite gives
     * that favorite, filed under the category if it had none.
     *
     * @return the favorite, and whether it was new
     */
    @Transactional
    public Map.Entry<Favorite, Boolean> createFromItem(String username, long itemId, String category) {
        Item item = items.findById(itemId)
                .filter(candidate -> username.equals(candidate.username()))
                .orElseThrow(() -> new NoSuchElementException("Item not found"));
        Optional<Favorite> existing = findFor(username, item.itemName(), item.brandId());
        if (existing.isPresent()) {
            Favorite favorite = existing.get();
            String wanted = category(username, category);
            if (favorite.category() == null && wanted != null) {
                favorite = update(favorite.favoriteId(), username, new FavoriteInput(favorite.name(), favorite.brandId(),
                        favorite.departmentId(), wanted, favorite.comment(), favorite.photoId(), null));
            }
            return Map.entry(favorite, false);
        }
        Map<Long, List<String>> listNames = listItems.findListNamesByItem(username);
        String wanted = normalize(item.itemName());
        Set<String> storeNames = new LinkedHashSet<>();
        items.findAllByUsername(username).stream()
                .filter(candidate -> normalize(candidate.itemName()).equals(wanted)
                        && Objects.equals(candidate.brandId(), item.brandId()))
                .sorted(Comparator.comparing(Item::itemId).reversed())
                .flatMap(candidate -> listNames.getOrDefault(candidate.itemId(), List.of()).stream())
                .map(stores::match)
                .flatMap(Optional::stream)
                .map(Store::name)
                .forEach(storeNames::add);
        Long photoId = item.photoId();
        if (photoId == null) {
            // Another item with the same name and brand may have the product's photo.
            photoId = items.findAllByUsername(username).stream()
                    .filter(candidate -> normalize(candidate.itemName()).equals(wanted)
                            && Objects.equals(candidate.brandId(), item.brandId()) && candidate.photoId() != null)
                    .max(Comparator.comparing(Item::itemId))
                    .map(Item::photoId)
                    .orElse(null);
        }
        Favorite created = create(username, new FavoriteInput(item.itemName(), item.brandId(), item.departmentId(),
                category, item.itemComment(), photoId,
                storeNames.stream().map(name -> new StoreInput(name, null, null, null)).toList()));
        return Map.entry(created, true);
    }

    /** Records the price seen at a store just now (adding the store to the favorite if it wasn't there). */
    @Transactional
    public Favorite recordPrice(long favoriteId, String username, StoreInput store) {
        Favorite favorite = get(favoriteId, username);
        FavoriteRepository.StoreRow row = storeRows(List.of(store), List.of()).getFirst();
        // A store already listed keeps its spelling ("Total Wine", not "Total wine").
        String name = favorite.stores().stream()
                .map(Favorite.StoreEntry::name)
                .filter(known -> known.equalsIgnoreCase(row.name()))
                .findFirst()
                .orElse(row.name());
        if (name.equals(row.name()) && favorite.stores().size() >= MAX_STORES
                && favorite.stores().stream().noneMatch(known -> known.name().equals(name))) {
            throw new IllegalArgumentException("A favorite can be sold at up to " + MAX_STORES + " stores.");
        }
        favorites.saveStore(favoriteId, new FavoriteRepository.StoreRow(name, row.price(), row.size(), row.productUrl(),
                row.price() == null ? null : OffsetDateTime.now()));
        favorites.touch(favoriteId);
        events.publishEvent(DataChangedEvent.favorites(username));
        return get(favoriteId, username);
    }

    /** Takes a store off the favorite (it isn't sold there). */
    @Transactional
    public Favorite removeStore(long favoriteId, String username, String storeName) {
        Favorite favorite = get(favoriteId, username);
        String name = storeName(storeName);
        Favorite.StoreEntry known = favorite.stores().stream()
                .filter(store -> store.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(favorite.name() + " isn't listed at " + name + "."));
        favorites.deleteStore(favoriteId, known.name());
        favorites.touch(favoriteId);
        events.publishEvent(DataChangedEvent.favorites(username));
        return get(favoriteId, username);
    }

    /**
     * Adds the favorite to a list as a new item with its name, brand, department, notes and photo, unless the list
     * has it already (an entry that was checked off is unchecked instead).
     */
    @Transactional
    public AddedToList addToList(long favoriteId, String username, long listId, Integer quantity) {
        Favorite favorite = get(favoriteId, username);
        if (quantity != null && (quantity < 1 || quantity > 999)) {
            throw new IllegalArgumentException("The quantity must be between 1 and 999.");
        }
        String wanted = normalize(favorite.name());
        ShoppingListItem existing = shopping.getShoppingItemsByList(listId, username).stream()
                .filter(item -> normalize(item.itemName()).equals(wanted))
                .filter(item -> item.brandId() == null || favorite.brandId() == null || item.brandId().equals(favorite.brandId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (existing.completed()) {
                shopping.updateShoppingItemCompletion(listId, existing.itemId(), username, false);
                return new AddedToList("unchecked", listId, existing.itemId());
            }
            return new AddedToList("already_on_list", listId, existing.itemId());
        }
        Item added = shopping.addShoppingItem(listId, favorite.name(), quantity == null ? 1 : quantity,
                favorite.departmentId(), favorite.brandId(), favorite.comment(), username);
        if (favorite.photoId() != null) {
            photoService.setItemPhoto(added.itemId(), favorite.photoId(), username);
        }
        return new AddedToList("added", listId, added.itemId());
    }

    // ---- Helpers ----

    private FavoriteRepository.Fields fields(String username, FavoriteInput input) {
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give the favorite a name.");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("The name can be at most 255 characters.");
        }
        if (input.brandId() != null && !brands.existsById(input.brandId())) {
            throw new IllegalArgumentException("Brand not found");
        }
        if (input.departmentId() != null && !departments.existsById(input.departmentId())) {
            throw new IllegalArgumentException("Department not found");
        }
        String comment = blankToNull(input.comment());
        if (comment != null && comment.length() > 1000) {
            throw new IllegalArgumentException("The notes can be at most 1000 characters.");
        }
        if (input.photoId() != null && !photos.belongsTo(input.photoId(), username)) {
            throw new IllegalArgumentException("Photo not found");
        }
        return new FavoriteRepository.Fields(name, input.brandId(), input.departmentId(), category(username, input.category()),
                comment, input.photoId());
    }

    /** A category as the user already spells it ("coffee" → "Coffee"), else with a capital first letter. */
    private String category(String username, String category) {
        String wanted = blankToNull(category);
        if (wanted == null) {
            return null;
        }
        wanted = wanted.replaceAll("\\s+", " ");
        if (wanted.length() > 100) {
            throw new IllegalArgumentException("The category can be at most 100 characters.");
        }
        String finalWanted = wanted;
        return favorites.categories(username).stream()
                .filter(known -> known.equalsIgnoreCase(finalWanted))
                .findFirst()
                .orElse(capitalize(wanted));
    }

    /**
     * The rows for these stores, one per store (the first wins). A price that didn't change keeps the time it was
     * seen; a new one is seen now.
     */
    private List<FavoriteRepository.StoreRow> storeRows(List<StoreInput> inputs, List<Favorite.StoreEntry> known) {
        if (inputs == null) {
            return List.of();
        }
        Map<String, FavoriteRepository.StoreRow> rows = new LinkedHashMap<>();
        for (StoreInput input : inputs) {
            String name = storeName(input.name());
            BigDecimal price = input.price();
            if (price != null && (price.signum() < 0 || price.compareTo(new BigDecimal("99999999")) > 0)) {
                throw new IllegalArgumentException("The price at " + name + " isn't a price.");
            }
            if (price != null) {
                price = price.setScale(2, RoundingMode.HALF_UP);
            }
            String size = blankToNull(input.size());
            if (size != null && size.length() > 100) {
                throw new IllegalArgumentException("The size can be at most 100 characters.");
            }
            String productUrl = blankToNull(input.productUrl());
            if (productUrl != null && (productUrl.length() > 2000 || !productUrl.matches("(?i)https?://\\S+"))) {
                throw new IllegalArgumentException("The product page at " + name + " must be a web address.");
            }
            BigDecimal finalPrice = price;
            OffsetDateTime checkedAt = price == null ? null : known.stream()
                    .filter(store -> store.name().equalsIgnoreCase(name) && store.price() != null
                            && store.price().compareTo(finalPrice) == 0)
                    .map(Favorite.StoreEntry::priceCheckedAt)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(OffsetDateTime.now());
            rows.putIfAbsent(name.toLowerCase(Locale.ROOT), new FavoriteRepository.StoreRow(name, price, size, productUrl, checkedAt));
        }
        if (rows.size() > MAX_STORES) {
            throw new IllegalArgumentException("A favorite can be sold at up to " + MAX_STORES + " stores.");
        }
        return List.copyOf(rows.values());
    }

    /** A store HList knows by its own name ("costco wholesale" → "Costco"), any other as given. */
    private String storeName(String name) {
        String wanted = blankToNull(name);
        if (wanted == null) {
            throw new IllegalArgumentException("Give the store's name.");
        }
        wanted = wanted.replaceAll("\\s+", " ");
        if (wanted.length() > 100) {
            throw new IllegalArgumentException("The store's name can be at most 100 characters.");
        }
        return stores.match(wanted).map(Store::name).orElse(capitalize(wanted));
    }

    private String storeKey(String storeName) {
        return stores.match(storeName).map(Store::key).orElse(null);
    }

    private static String capitalize(String value) {
        int first = value.codePointAt(0);
        return new StringBuilder(value.length())
                .appendCodePoint(Character.toTitleCase(first))
                .append(value, Character.charCount(first), value.length())
                .toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
