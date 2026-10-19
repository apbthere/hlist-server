package com.abcconsulting.slme.server.demo;

import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.photos.PhotoService;
import com.abcconsulting.slme.server.photos.ProductPhotoFinder;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo mode (HLIST_DEMO=true, which the Dockerfile's demo target sets): on start, an empty database gets a demo
 * account with lists, a past trip and a catalog, and the items' product photos are fetched from Open Food Facts in
 * the background. A database that has any account is never touched.
 */
@Component
@ConditionalOnProperty(name = "hlist.demo", havingValue = "true")
public class DemoData implements ApplicationRunner {

    public static final String USERNAME = "demo";
    public static final String PASSWORD = "demo";

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    record DemoItem(String name, String brand, String department, int quantity, String comment, boolean checked) {
        DemoItem(String name, String brand, String department) {
            this(name, brand, department, 1, null, false);
        }
    }

    record DemoList(String name, int daysAgo, boolean completed, List<DemoItem> items) {
    }

    static final List<String> DEPARTMENTS =
            List.of("Produce", "Dairy & Eggs", "Bakery", "Meat & Seafood", "Pantry", "Household", "Frozen");

    /** Oldest first, so the newest list ends up on top. The completed Costco trip is what "the usual" refers to. */
    static final List<DemoList> LISTS = List.of(
            new DemoList("Costco", 14, true, List.of(
                    new DemoItem("Oat Milk", "Oatly", "Dairy & Eggs", 2, "Full fat", false),
                    new DemoItem("Paper Towels", "Kirkland Signature", "Household", 1, "12 rolls", false),
                    new DemoItem("Salmon Fillets", "Kirkland Signature", "Meat & Seafood"),
                    new DemoItem("Blueberries", null, "Produce"))),
            new DemoList("Target", 6, false, List.of(
                    new DemoItem("Laundry Detergent", "Tide", "Household"),
                    new DemoItem("Sparkling Water", "LaCroix", "Pantry", 2, "Lime", false))),
            new DemoList("Publix Weekly", 2, false, List.of(
                    new DemoItem("Bananas", null, "Produce", 6, null, false),
                    new DemoItem("Sourdough Bread", null, "Bakery"),
                    new DemoItem("Orange Juice", "Tropicana", "Dairy & Eggs", 1, "No pulp", false),
                    new DemoItem("Ground Coffee", "Starbucks", "Pantry"))),
            new DemoList("Costco Run", 0, false, List.of(
                    new DemoItem("Strawberries", null, "Produce"),
                    new DemoItem("Avocados", null, "Produce", 1, "Bag of 5", false),
                    new DemoItem("Greek Yogurt", "Chobani", "Dairy & Eggs", 2, "Plain, 32 oz", false),
                    new DemoItem("Cheddar Cheese", "Tillamook", "Dairy & Eggs"),
                    new DemoItem("Peanut Butter", "Jif", "Pantry", 1, "Creamy", false),
                    new DemoItem("Eggs", null, "Dairy & Eggs", 2, null, true))));

    private final UserAccountService users;
    private final ShoppingListService shopping;
    private final PhotoService photos;
    private final ProductPhotoFinder photoFinder;
    private final JdbcTemplate jdbcTemplate;
    private final boolean fetchPhotos;

    public DemoData(UserAccountService users, ShoppingListService shopping, PhotoService photos,
            ProductPhotoFinder photoFinder, JdbcTemplate jdbcTemplate,
            @Value("${hlist.demo.photos:true}") boolean fetchPhotos) {
        this.users = users;
        this.shopping = shopping;
        this.photos = photos;
        this.photoFinder = photoFinder;
        this.jdbcTemplate = jdbcTemplate;
        this.fetchPhotos = fetchPhotos;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Integer accounts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (accounts != null && accounts > 0) {
            log.info("Demo mode: the database already has accounts, so no demo data was added");
            return;
        }
        List<PhotoJob> jobs = seed();
        log.info("Demo mode: sign in as \"{}\" with the password \"{}\"", USERNAME, PASSWORD);
        if (fetchPhotos) {
            Thread.ofVirtual().name("demo-photos").start(() -> fetchPhotos(jobs));
        }
    }

    private record PhotoJob(long itemId, String name, String brand) {
    }

    private List<PhotoJob> seed() {
        users.createUser(USERNAME, PASSWORD);
        Map<String, Long> departments = new HashMap<>();
        shopping.getDepartments().forEach(department -> departments.put(department.departmentName(), department.departmentId()));
        DEPARTMENTS.forEach(name -> departments.computeIfAbsent(name, missing -> shopping.addDepartment(missing).departmentId()));
        Map<String, Long> brands = new HashMap<>();
        shopping.getBrands().forEach(brand -> brands.put(brand.brandName(), brand.brandId()));

        OffsetDateTime now = OffsetDateTime.now();
        List<PhotoJob> jobs = new ArrayList<>();
        for (DemoList demoList : LISTS) {
            ShoppingList list = shopping.addShoppingList(demoList.name(), USERNAME);
            List<Long> itemIds = new ArrayList<>();
            for (DemoItem demoItem : demoList.items()) {
                Long brandId = demoItem.brand() == null ? null
                        : brands.computeIfAbsent(demoItem.brand(), missing -> shopping.addBrand(missing).brandId());
                Item item = shopping.addShoppingItem(list.shoppingListId(), demoItem.name(), demoItem.quantity(),
                        departments.get(demoItem.department()), brandId, demoItem.comment(), USERNAME);
                if (demoItem.checked()) {
                    shopping.updateShoppingItemCompletion(list.shoppingListId(), item.itemId(), USERNAME, true);
                }
                itemIds.add(item.itemId());
                jobs.add(new PhotoJob(item.itemId(), demoItem.name(), demoItem.brand()));
            }
            if (demoList.completed()) {
                shopping.updateShoppingListCompletion(list.shoppingListId(), USERNAME, true);
            }
            backdate(list.shoppingListId(), itemIds, now.minusDays(demoList.daysAgo()));
        }
        return jobs;
    }

    /** The database sets timestamps itself, so lists from "two weeks ago" are dated here. */
    private void backdate(long listId, List<Long> itemIds, OffsetDateTime when) {
        jdbcTemplate.update("UPDATE shopping_list SET created_at = ?, updated_at = ? WHERE shopping_list_id = ?",
                when, when, listId);
        jdbcTemplate.update("UPDATE shopping_list_item SET created_at = ?, updated_at = ? WHERE shopping_list_id = ?",
                when, when, listId);
        for (Long itemId : itemIds) {
            jdbcTemplate.update("UPDATE item SET created_at = ?, updated_at = ? WHERE item_id = ?", when, when, itemId);
        }
    }

    /** The first Open Food Facts photo for each item, trying twice; items without a match keep no photo. */
    private void fetchPhotos(List<PhotoJob> jobs) {
        int found = 0;
        for (PhotoJob job : jobs) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    List<ProductPhotoFinder.Suggestion> suggestions = photoFinder.find(job.name(), job.brand());
                    if (suggestions.isEmpty()) {
                        continue;
                    }
                    PhotoService.ImportedPhoto photo = photos.importFromWeb(USERNAME, suggestions.getFirst().imageUrl());
                    photos.setItemPhoto(job.itemId(), photo.photoId(), USERNAME);
                    found++;
                    break;
                } catch (RuntimeException failed) {
                    log.debug("Demo photo for {} failed: {}", job.name(), failed.getMessage());
                }
            }
        }
        log.info("Demo mode: product photos for {} of {} items", found, jobs.size());
    }
}
