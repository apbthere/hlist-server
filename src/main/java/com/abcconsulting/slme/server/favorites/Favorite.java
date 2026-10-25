package com.abcconsulting.slme.server.favorites;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A product the user buys again and again, kept apart from any list or store.
 *
 * @param category the user's own grouping ("Coffee", "Sake"), or null
 * @param stores   where it's sold, cheapest known price first
 */
public record Favorite(
        long favoriteId,
        String name,
        Long brandId,
        Long departmentId,
        String category,
        String comment,
        /** The favorite's photo (GET /api/photos/{photoId}), or null. Items added from it show the same one. */
        Long photoId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<StoreEntry> stores) {

    /**
     * A store the favorite is sold at.
     *
     * @param name           e.g. "Costco" (a store HList knows is always spelled its way) or "Total Wine"
     * @param key            the store's key when HList knows it (icon at /api/store-icons/{key}), else null
     * @param price          the price last seen there, or null
     * @param size           what that price is for, e.g. "2 lb bag" or "6 pack"
     * @param productUrl     the product's page at that store
     * @param priceCheckedAt when the price was seen
     */
    public record StoreEntry(String name, String key, BigDecimal price, String size, String productUrl,
            OffsetDateTime priceCheckedAt) {
    }
}
