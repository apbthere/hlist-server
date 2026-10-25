package com.abcconsulting.slme.server.live;

/**
 * Something that other open clients may want to reload. Published by the services and forwarded to the
 * clients' live-update streams once the change is committed (see {@link LiveUpdateService}).
 *
 * @param username whose data changed, or null for shared data every user sees (departments and brands)
 * @param type     "lists", "list-items", "items", "favorites" or "catalog"
 * @param listId   the shopping list, for "list-items"
 */
public record DataChangedEvent(String username, String type, Long listId) {

    public static DataChangedEvent lists(String username) {
        return new DataChangedEvent(username, "lists", null);
    }

    public static DataChangedEvent listItems(String username, Long listId) {
        return new DataChangedEvent(username, "list-items", listId);
    }

    public static DataChangedEvent items(String username) {
        return new DataChangedEvent(username, "items", null);
    }

    public static DataChangedEvent favorites(String username) {
        return new DataChangedEvent(username, "favorites", null);
    }

    public static DataChangedEvent catalog() {
        return new DataChangedEvent(null, "catalog", null);
    }
}
