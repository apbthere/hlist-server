package com.abcconsulting.slme.server.model;

import java.time.OffsetDateTime;

public record ShoppingListItem(
        Long itemId,
        String itemName,
        Integer quantity,
        Long departmentId,
        Long brandId,
        String itemComment,
        String username,
        boolean completed,
        /** When the item was added to the list. */
        OffsetDateTime createdAt,
        /** When the item's entry on the list last changed, e.g. was checked off. */
        OffsetDateTime updatedAt,
        /** The item's photo (GET /api/photos/{photoId}), or null. */
        Long photoId) {
}