package com.abcconsulting.slme.server.favorites;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@Validated
public class FavoriteController {

    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    public List<Favorite> getFavorites(Authentication authentication) {
        return favorites.list(authentication.getName());
    }

    @PostMapping
    public ResponseEntity<Favorite> createFavorite(Authentication authentication,
            @Valid @RequestBody FavoriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(favorites.create(authentication.getName(), request.input()));
    }

    /** Makes an item (e.g. one on a list) a favorite; an item that already is one gives that favorite (200). */
    @PostMapping("/from-item")
    public ResponseEntity<Favorite> createFromItem(Authentication authentication,
            @Valid @RequestBody FromItemRequest request) {
        Map.Entry<Favorite, Boolean> result = favorites.createFromItem(
                authentication.getName(), request.itemId(), request.category());
        return ResponseEntity.status(result.getValue() ? HttpStatus.CREATED : HttpStatus.OK).body(result.getKey());
    }

    @PutMapping("/{favoriteId}")
    public Favorite updateFavorite(Authentication authentication, @PathVariable long favoriteId,
            @Valid @RequestBody FavoriteRequest request) {
        return favorites.update(favoriteId, authentication.getName(), request.input());
    }

    @DeleteMapping("/{favoriteId}")
    public ResponseEntity<Void> deleteFavorite(Authentication authentication, @PathVariable long favoriteId) {
        favorites.delete(favoriteId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{favoriteId}/add-to-list")
    public FavoriteService.AddedToList addToList(Authentication authentication, @PathVariable long favoriteId,
            @Valid @RequestBody AddToListRequest request) {
        return favorites.addToList(favoriteId, authentication.getName(), request.listId(), request.quantity());
    }

    public record FavoriteRequest(
            @NotBlank @Size(max = 255) String name,
            Long brandId,
            Long departmentId,
            @Size(max = 100) String category,
            @Size(max = 1000) String comment,
            Long photoId,
            /** Where it's sold; null keeps the stores it has. */
            @Size(max = FavoriteService.MAX_STORES) List<@Valid StoreRequest> stores) {

        FavoriteService.FavoriteInput input() {
            return new FavoriteService.FavoriteInput(name, brandId, departmentId, category, comment, photoId,
                    stores == null ? null : stores.stream()
                            .map(store -> new FavoriteService.StoreInput(store.name(), store.price(), store.size(), store.productUrl()))
                            .toList());
        }
    }

    public record StoreRequest(
            @NotBlank @Size(max = 100) String name,
            BigDecimal price,
            @Size(max = 100) String size,
            @Size(max = 2000) String productUrl) {
    }

    public record FromItemRequest(@NotNull Long itemId, @Size(max = 100) String category) {
    }

    public record AddToListRequest(@NotNull Long listId, @Min(1) @Max(999) Integer quantity) {
    }
}
