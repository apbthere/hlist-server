package com.abcconsulting.slme.server.model;

import com.abcconsulting.slme.server.stores.Store;

import java.time.OffsetDateTime;

/**
 * A shopping list as the API returns it: the stored list plus the store its name mentions, if any
 * (icon at /api/store-icons/{store.key}).
 */
public record ShoppingListView(
        Long shoppingListId,
        String shoppingListName,
        String username,
        boolean completed,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        StoreRef store) {

    public record StoreRef(String key, String name) {
    }

    public static ShoppingListView of(ShoppingList list, Store store) {
        return new ShoppingListView(list.shoppingListId(), list.shoppingListName(), list.username(), list.completed(),
                list.createdAt(), list.updatedAt(), store == null ? null : new StoreRef(store.key(), store.name()));
    }
}
