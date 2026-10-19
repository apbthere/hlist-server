package com.abcconsulting.slme.server.service;

import com.abcconsulting.slme.server.live.DataChangedEvent;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.model.Department;
import com.abcconsulting.slme.server.model.Brand;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingListItem;
import com.abcconsulting.slme.server.repository.BrandRepository;
import com.abcconsulting.slme.server.repository.DepartmentRepository;
import com.abcconsulting.slme.server.repository.ItemRepository;
import com.abcconsulting.slme.server.repository.ShoppingListRepository;
import com.abcconsulting.slme.server.repository.ShoppingListItemDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Validated
public class ShoppingListService {

    private final ShoppingListRepository shoppingListRepository;
    private final DepartmentRepository departmentRepository;
    private final BrandRepository brandRepository;
    private final ItemRepository itemRepository;
    private final ShoppingListItemDao shoppingListItemDao;
    private final ApplicationEventPublisher events;

    public ShoppingListService(
            ShoppingListRepository shoppingListRepository,
            DepartmentRepository departmentRepository,
            BrandRepository brandRepository,
            ItemRepository itemRepository,
            ShoppingListItemDao shoppingListItemDao,
            ApplicationEventPublisher events) {
        this.shoppingListRepository = shoppingListRepository;
        this.departmentRepository = departmentRepository;
        this.brandRepository = brandRepository;
        this.itemRepository = itemRepository;
        this.shoppingListItemDao = shoppingListItemDao;
        this.events = events;
    }

    /** The user's lists, newest first (lists created in the same instant: the later one first). */
    public Page<ShoppingList> getShoppingListsByUser(
            @NotNull String username,
            @NotNull Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("shoppingListId")));
        List<ShoppingList> shoppingLists = shoppingListRepository.findAllByUsername(username, newestFirst);
        return new PageImpl<>(shoppingLists, newestFirst, shoppingListRepository.countByUsername(username));
    }

    public ShoppingList addShoppingList(
            @NotNull String shoppingListName,
            @NotNull String username) {
        ShoppingList saved = shoppingListRepository.save(
                new ShoppingList(null, capitalizeFirstLetter(shoppingListName), username, false, null, null));
        events.publishEvent(DataChangedEvent.lists(username));
        // Reload to pick up the timestamps the database filled in.
        return shoppingListRepository.findById(saved.shoppingListId()).orElse(saved);
    }

    @Transactional
    public boolean updateShoppingListCompletion(
            @NotNull Long shoppingListId,
            @NotNull String username,
            boolean completed) {
        ShoppingList shoppingList = shoppingListRepository.findById(shoppingListId)
                .filter(list -> username.equals(list.username()))
                .orElse(null);
        if (shoppingList == null) {
            return false;
        }
        shoppingListRepository.save(new ShoppingList(
                shoppingList.shoppingListId(), shoppingList.shoppingListName(), shoppingList.username(), completed,
                shoppingList.createdAt(), shoppingList.updatedAt()));
        events.publishEvent(DataChangedEvent.lists(username));
        return true;
    }

    public Department addDepartment(@NotNull String departmentName) {
        Department department = departmentRepository.save(new Department(null, departmentName));
        events.publishEvent(DataChangedEvent.catalog());
        return department;
    }

    public List<Department> getDepartments() {
        return (List<Department>) departmentRepository.findAll();
    }

    public List<Brand> getBrands() {
        return (List<Brand>) brandRepository.findAll();
    }

    public List<Item> getItemsByUser(@NotNull String username) {
        return itemRepository.findAllByUsername(username);
    }

    public Brand addBrand(@NotNull String brandName) {
        Brand brand = brandRepository.save(new Brand(null, brandName));
        events.publishEvent(DataChangedEvent.catalog());
        return brand;
    }

    @Transactional
    public Item addShoppingItem(
            @NotNull Long shoppingListId,
            @NotNull String itemName,
            Long departmentId,
            Long brandId,
            String itemComment,
            @NotNull String username) {
        return addShoppingItem(
                shoppingListId, itemName, 1, departmentId, brandId, itemComment, username);
    }

    @Transactional
    public Item addShoppingItem(
            @NotNull Long shoppingListId,
            @NotNull String itemName,
            Integer quantity,
            Long departmentId,
            Long brandId,
            String itemComment,
            @NotNull String username) {
        Item item = saveAndReload(new Item(
            null, itemName, quantity == null ? 1 : quantity, departmentId, brandId, itemComment, username));
        if (shoppingListItemDao.addItemToList(shoppingListId, item.itemId(), username) == 0) {
            throw new NoSuchElementException("Shopping list not found for user");
        }
        synchronizeShoppingListCompletion(shoppingListId, username);
        publishListChange(username, shoppingListId);
        return item;
    }

    public List<ShoppingListItem> getShoppingItemsByList(@NotNull Long shoppingListId, @NotNull String username) {
        return shoppingListItemDao.findItemsByListAndUsername(shoppingListId, username);
    }

    @Transactional
    public boolean updateShoppingItemCompletion(
            @NotNull Long shoppingListId,
            @NotNull Long itemId,
            @NotNull String username,
            boolean completed) {
        boolean updated = shoppingListItemDao.updateItemCompletion(shoppingListId, itemId, username, completed) > 0;
        if (updated) {
            synchronizeShoppingListCompletion(shoppingListId, username);
            publishListChange(username, shoppingListId);
        }
        return updated;
    }

    @Transactional
        public Item updateShoppingItem(
            @NotNull Long shoppingListId,
            @NotNull @Valid Item item,
            @NotNull String username) {
        if (item.itemId() == null
            || !username.equals(item.username())
                || !shoppingListItemDao.itemBelongsToListAndUsername(
                shoppingListId, item.itemId(), username)) {
            throw new NoSuchElementException("Shopping item not found in list for user");
        }
        Integer quantity = item.quantity();
        if (quantity == null) {
            quantity = itemRepository.findById(item.itemId()).map(existingItem -> existingItem.quantity()).orElse(1);
        }
        Item saved = saveAndReload(new Item(
            item.itemId(), item.itemName(), quantity, item.departmentId(), item.brandId(), item.itemComment(), item.username()));
        publishListChange(username, shoppingListId);
        return saved;
    }

    @Transactional
    public boolean deleteShoppingItemFromList(
            @NotNull Long shoppingListId,
            @NotNull Long itemId,
            @NotNull String username) {
        boolean deleted = shoppingListItemDao.deleteItemFromList(shoppingListId, itemId, username) > 0;
        if (deleted) {
            synchronizeShoppingListCompletion(shoppingListId, username);
            publishListChange(username, shoppingListId);
        }
        return deleted;
    }

    @Transactional
    public Item addItem(
            @NotNull String itemName,
            Integer quantity,
            Long departmentId,
            Long brandId,
            String itemComment,
            @NotNull String username) {
        checkCatalogReferences(departmentId, brandId);
        Item saved = saveAndReload(new Item(
                null, itemName, quantity == null ? 1 : quantity, departmentId, brandId, itemComment, username));
        events.publishEvent(DataChangedEvent.items(username));
        return saved;
    }

    @Transactional
    public Item updateItem(@NotNull @Valid Item item, @NotNull String username) {
        Item existing = findOwnItem(item.itemId(), username);
        checkCatalogReferences(item.departmentId(), item.brandId());
        Integer quantity = item.quantity() == null ? existing.quantity() : item.quantity();
        Item saved = saveAndReload(new Item(
                existing.itemId(), item.itemName(), quantity, item.departmentId(), item.brandId(), item.itemComment(), username));
        events.publishEvent(DataChangedEvent.items(username));
        shoppingListItemDao.findListIdsContainingItem(saved.itemId())
                .forEach(listId -> events.publishEvent(DataChangedEvent.listItems(username, listId)));
        return saved;
    }

    /** Deletes one of the user's items, removing it from any shopping lists it is on. */
    @Transactional
    public void deleteItem(@NotNull Long itemId, @NotNull String username) {
        Item item = findOwnItem(itemId, username);
        List<Long> listIds = shoppingListItemDao.findListIdsContainingItem(item.itemId());
        shoppingListItemDao.deleteItemFromAllLists(item.itemId());
        itemRepository.deleteById(item.itemId());
        listIds.forEach(listId -> synchronizeShoppingListCompletion(listId, username));
        events.publishEvent(DataChangedEvent.items(username));
        listIds.forEach(listId -> events.publishEvent(DataChangedEvent.listItems(username, listId)));
        if (!listIds.isEmpty()) {
            events.publishEvent(DataChangedEvent.lists(username));
        }
    }

    @Transactional
    public Department renameDepartment(@NotNull Long departmentId, @NotNull String departmentName) {
        if (!departmentRepository.existsById(departmentId)) {
            throw new NoSuchElementException("Department not found");
        }
        Department department = departmentRepository.save(new Department(departmentId, departmentName));
        events.publishEvent(DataChangedEvent.catalog());
        return department;
    }

    /** Departments are shared by all users, so one still used by any item can't be deleted. */
    @Transactional
    public void deleteDepartment(@NotNull Long departmentId) {
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new NoSuchElementException("Department not found"));
        int usage = shoppingListItemDao.countItemsWithDepartment(departmentId);
        if (usage > 0) {
            throw new InUseException(department.departmentName() + " is used by " + usage
                    + (usage == 1 ? " item" : " items") + ". Change or delete those items first.");
        }
        departmentRepository.deleteById(departmentId);
        events.publishEvent(DataChangedEvent.catalog());
    }

    @Transactional
    public Brand renameBrand(@NotNull Long brandId, @NotNull String brandName) {
        if (!brandRepository.existsById(brandId)) {
            throw new NoSuchElementException("Brand not found");
        }
        Brand brand = brandRepository.save(new Brand(brandId, brandName));
        events.publishEvent(DataChangedEvent.catalog());
        return brand;
    }

    /** Brands are shared by all users, so one still used by any item can't be deleted. */
    @Transactional
    public void deleteBrand(@NotNull Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new NoSuchElementException("Brand not found"));
        int usage = shoppingListItemDao.countItemsWithBrand(brandId);
        if (usage > 0) {
            throw new InUseException(brand.brandName() + " is used by " + usage
                    + (usage == 1 ? " item" : " items") + ". Change or delete those items first.");
        }
        brandRepository.deleteById(brandId);
        events.publishEvent(DataChangedEvent.catalog());
    }

    /**
     * A change to a list's items: the list's items, the user's item records and, since the list may have become
     * (in)complete, the user's lists.
     */
    private void publishListChange(String username, Long shoppingListId) {
        events.publishEvent(DataChangedEvent.listItems(username, shoppingListId));
        events.publishEvent(DataChangedEvent.items(username));
        events.publishEvent(DataChangedEvent.lists(username));
    }

    /** Saves an item and re-reads it, so the result carries the timestamps the database filled in. */
    private Item saveAndReload(Item item) {
        Item saved = itemRepository.save(item);
        return itemRepository.findById(saved.itemId()).orElse(saved);
    }

    private Item findOwnItem(Long itemId, String username) {
        return itemRepository.findById(itemId)
                .filter(item -> username.equals(item.username()))
                .orElseThrow(() -> new NoSuchElementException("Item not found"));
    }

    private void checkCatalogReferences(Long departmentId, Long brandId) {
        if (departmentId != null && !departmentRepository.existsById(departmentId)) {
            throw new IllegalArgumentException("Department not found");
        }
        if (brandId != null && !brandRepository.existsById(brandId)) {
            throw new IllegalArgumentException("Brand not found");
        }
    }

    /** "weekend shop" becomes "Weekend shop"; the rest of the name is kept as typed. */
    static String capitalizeFirstLetter(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int first = trimmed.codePointAt(0);
        return new StringBuilder(trimmed.length())
                .appendCodePoint(Character.toTitleCase(first))
                .append(trimmed, Character.charCount(first), trimmed.length())
                .toString();
    }

    private void synchronizeShoppingListCompletion(Long shoppingListId, String username) {
        List<ShoppingListItem> items = shoppingListItemDao.findItemsByListAndUsername(shoppingListId, username);
        boolean completed = !items.isEmpty() && items.stream().allMatch(item -> item.completed());
        shoppingListRepository.findById(shoppingListId)
                .filter(list -> username.equals(list.username()))
                .filter(list -> list.completed() != completed)
                .ifPresent(list -> shoppingListRepository.save(new ShoppingList(
                        list.shoppingListId(), list.shoppingListName(), list.username(), completed,
                        list.createdAt(), list.updatedAt())));
    }
}