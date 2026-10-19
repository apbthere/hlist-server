package com.abcconsulting.slme.server.controller;

import com.abcconsulting.slme.server.model.Department;
import com.abcconsulting.slme.server.model.Brand;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.model.ShoppingList;
import com.abcconsulting.slme.server.model.ShoppingListView;
import com.abcconsulting.slme.server.model.ShoppingListItem;
import com.abcconsulting.slme.server.service.ShoppingListService;
import com.abcconsulting.slme.server.stores.StoreCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class ShoppingController {

    private final ShoppingListService shoppingListService;
    private final StoreCatalog storeCatalog;

    public ShoppingController(ShoppingListService shoppingListService, StoreCatalog storeCatalog) {
        this.shoppingListService = shoppingListService;
        this.storeCatalog = storeCatalog;
    }

    /** Adds the store the list's name mentions ("Costco run" → Costco), if any. */
    private ShoppingListView view(ShoppingList list) {
        return ShoppingListView.of(list, storeCatalog.match(list.shoppingListName()).orElse(null));
    }

    @GetMapping("/shopping-lists")
        public PageResponse<ShoppingListView> getShoppingLists(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<ShoppingList> results = shoppingListService.getShoppingListsByUser(
                authentication.getName(), PageRequest.of(page, size));
        return new PageResponse<>(
                results.getContent().stream().map(this::view).toList(),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages(),
                results.isFirst(),
                results.isLast());
    }

    @PostMapping("/shopping-lists")
    public ResponseEntity<ShoppingListView> createShoppingList(
            Authentication authentication,
            @Valid @RequestBody NameRequest request) {
        ShoppingList shoppingList = shoppingListService.addShoppingList(
                request.name(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(shoppingList));
    }

    @PostMapping("/departments")
    public ResponseEntity<Department> createDepartment(@Valid @RequestBody NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shoppingListService.addDepartment(request.name()));
    }

        @GetMapping("/departments")
        public List<Department> getDepartments() {
                return shoppingListService.getDepartments();
        }

        @GetMapping("/brands")
        public List<Brand> getBrands() {
                return shoppingListService.getBrands();
        }

        @GetMapping("/items")
        public List<Item> getItems(Authentication authentication) {
                return shoppingListService.getItemsByUser(authentication.getName());
        }

        @PostMapping("/brands")
        public ResponseEntity<Brand> createBrand(@Valid @RequestBody NameRequest request) {
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(shoppingListService.addBrand(request.name()));
        }

    @PostMapping("/shopping-lists/{shoppingListId}/items")
    public ResponseEntity<Item> createShoppingItem(
            Authentication authentication,
            @PathVariable Long shoppingListId,
            @Valid @RequestBody ShoppingItemRequest request) {
        Item item = shoppingListService.addShoppingItem(
                shoppingListId,
                request.name(),
                request.quantity(),
                request.departmentId(),
                request.brandId(),
                request.comment(),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @GetMapping("/shopping-lists/{shoppingListId}/items")
        public List<ShoppingListItem> getShoppingItems(
            Authentication authentication,
            @PathVariable Long shoppingListId) {
        return shoppingListService.getShoppingItemsByList(shoppingListId, authentication.getName());
    }

        @PatchMapping("/shopping-lists/{shoppingListId}/items/{itemId}/completion")
        public ResponseEntity<Void> updateShoppingItemCompletion(
                        Authentication authentication,
                        @PathVariable Long shoppingListId,
                        @PathVariable Long itemId,
                        @Valid @RequestBody CompletionRequest request) {
                boolean updated = shoppingListService.updateShoppingItemCompletion(
                                shoppingListId, itemId, authentication.getName(), request.completed());
                return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }

    @PutMapping("/shopping-lists/{shoppingListId}/items/{itemId}")
    public Item updateShoppingItem(
            Authentication authentication,
            @PathVariable Long shoppingListId,
            @PathVariable Long itemId,
            @Valid @RequestBody ShoppingItemRequest request) {
        Item item = new Item(
                itemId,
                request.name(),
                request.quantity(),
                request.departmentId(),
                request.brandId(),
                request.comment(),
                authentication.getName());
        return shoppingListService.updateShoppingItem(shoppingListId, item, authentication.getName());
    }

    @DeleteMapping("/shopping-lists/{shoppingListId}/items/{itemId}")
    public ResponseEntity<Void> deleteShoppingItem(
            Authentication authentication,
            @PathVariable Long shoppingListId,
            @PathVariable Long itemId) {
        boolean deleted = shoppingListService.deleteShoppingItemFromList(
                shoppingListId, itemId, authentication.getName());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

        @PatchMapping("/shopping-lists/{shoppingListId}/completion")
        public ResponseEntity<Void> updateShoppingListCompletion(
                        Authentication authentication,
                        @PathVariable Long shoppingListId,
                        @Valid @RequestBody CompletionRequest request) {
                boolean updated = shoppingListService.updateShoppingListCompletion(
                                shoppingListId, authentication.getName(), request.completed());
                return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }

    @PostMapping("/items")
    public ResponseEntity<Item> createItem(
            Authentication authentication,
            @Valid @RequestBody ShoppingItemRequest request) {
        Item item = shoppingListService.addItem(
                request.name(), request.quantity(), request.departmentId(), request.brandId(), request.comment(),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @PutMapping("/items/{itemId}")
    public Item updateItem(
            Authentication authentication,
            @PathVariable Long itemId,
            @Valid @RequestBody ShoppingItemRequest request) {
        return shoppingListService.updateItem(new Item(
                itemId, request.name(), request.quantity(), request.departmentId(), request.brandId(), request.comment(),
                authentication.getName()), authentication.getName());
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(Authentication authentication, @PathVariable Long itemId) {
        shoppingListService.deleteItem(itemId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/departments/{departmentId}")
    public Department renameDepartment(@PathVariable Long departmentId, @Valid @RequestBody NameRequest request) {
        return shoppingListService.renameDepartment(departmentId, request.name());
    }

    @DeleteMapping("/departments/{departmentId}")
    public ResponseEntity<Void> deleteDepartment(@PathVariable Long departmentId) {
        shoppingListService.deleteDepartment(departmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/brands/{brandId}")
    public Brand renameBrand(@PathVariable Long brandId, @Valid @RequestBody NameRequest request) {
        return shoppingListService.renameBrand(brandId, request.name());
    }

    @DeleteMapping("/brands/{brandId}")
    public ResponseEntity<Void> deleteBrand(@PathVariable Long brandId) {
        shoppingListService.deleteBrand(brandId);
        return ResponseEntity.noContent().build();
    }

    public record NameRequest(@NotBlank @Size(max = 255) String name) {
    }

    public record ShoppingItemRequest(
            @NotBlank @Size(max = 255) String name,
            @Min(1) Integer quantity,
            Long departmentId,
            Long brandId,
            @Size(max = 1000) String comment) {
    }

    public record CompletionRequest(@NotNull Boolean completed) {
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
    }
}