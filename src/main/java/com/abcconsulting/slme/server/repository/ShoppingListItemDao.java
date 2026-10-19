package com.abcconsulting.slme.server.repository;

import com.abcconsulting.slme.server.model.ShoppingListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ShoppingListItemDao {

    private final JdbcTemplate jdbcTemplate;

    public ShoppingListItemDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShoppingListItem> findItemsByListAndUsername(Long shoppingListId, String username) {
        return jdbcTemplate.query("""
                SELECT i.item_id, i.item_name, i.quantity, i.department_id, i.brand_id, i.item_comment, i.username,
                    sli.completed, sli.created_at, sli.updated_at, i.photo_id
                FROM shopping_list_item sli
                JOIN shopping_list sl ON sl.shopping_list_id = sli.shopping_list_id
                JOIN item i ON i.item_id = sli.item_id
                WHERE sl.shopping_list_id = ? AND sl.username = ? AND i.username = sl.username
                ORDER BY i.item_id
                """, (resultSet, rowNumber) -> new ShoppingListItem(
                resultSet.getLong("item_id"),
                resultSet.getString("item_name"),
                resultSet.getInt("quantity"),
                resultSet.getObject("department_id", Long.class),
                resultSet.getObject("brand_id", Long.class),
                resultSet.getString("item_comment"),
                resultSet.getString("username"),
                resultSet.getBoolean("completed"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getObject("photo_id", Long.class)), shoppingListId, username);
    }

    public boolean itemBelongsToListAndUsername(Long shoppingListId, Long itemId, String username) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM shopping_list_item sli
                JOIN shopping_list sl ON sl.shopping_list_id = sli.shopping_list_id
                JOIN item i ON i.item_id = sli.item_id
                WHERE sl.shopping_list_id = ? AND i.item_id = ?
                    AND sl.username = ? AND i.username = sl.username
                """, Integer.class, shoppingListId, itemId, username);
        return count != null && count > 0;
    }

    public int addItemToList(Long shoppingListId, Long itemId, String username) {
        return jdbcTemplate.update("""
                INSERT INTO shopping_list_item (shopping_list_id, item_id)
                SELECT sl.shopping_list_id, i.item_id
                FROM shopping_list sl
                JOIN item i ON i.item_id = ?
                WHERE sl.shopping_list_id = ? AND sl.username = ? AND i.username = sl.username
                """, itemId, shoppingListId, username);
    }

    public int deleteItemFromList(Long shoppingListId, Long itemId, String username) {
        return jdbcTemplate.update("""
                DELETE FROM shopping_list_item
                WHERE shopping_list_id = ? AND item_id = ?
                    AND EXISTS (
                        SELECT 1
                        FROM shopping_list sl
                        JOIN item i ON i.item_id = ?
                        WHERE sl.shopping_list_id = ?
                            AND sl.username = ? AND i.username = sl.username
                    )
                """, shoppingListId, itemId, itemId, shoppingListId, username);
    }

    public List<Long> findListIdsContainingItem(Long itemId) {
        return jdbcTemplate.queryForList(
                "SELECT shopping_list_id FROM shopping_list_item WHERE item_id = ?", Long.class, itemId);
    }

    public int deleteItemFromAllLists(Long itemId) {
        return jdbcTemplate.update("DELETE FROM shopping_list_item WHERE item_id = ?", itemId);
    }

    public int countItemsWithDepartment(Long departmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item WHERE department_id = ?", Integer.class, departmentId);
        return count == null ? 0 : count;
    }

    public int countItemsWithBrand(Long brandId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item WHERE brand_id = ?", Integer.class, brandId);
        return count == null ? 0 : count;
    }

    /** For each of the user's items that is on a list, the names of the lists it is on. */
    public Map<Long, List<String>> findListNamesByItem(String username) {
        Map<Long, List<String>> names = new HashMap<>();
        jdbcTemplate.query("""
                SELECT sli.item_id, sl.shopping_list_name
                FROM shopping_list_item sli
                JOIN shopping_list sl ON sl.shopping_list_id = sli.shopping_list_id
                WHERE sl.username = ?
                """, (RowCallbackHandler) resultSet -> names
                .computeIfAbsent(resultSet.getLong("item_id"), id -> new ArrayList<>())
                .add(resultSet.getString("shopping_list_name")), username);
        return names;
    }

    public int updateItemCompletion(
            Long shoppingListId, Long itemId, String username, boolean completed) {
        return jdbcTemplate.update("""
                UPDATE shopping_list_item
                SET completed = ?
                WHERE shopping_list_id = ? AND item_id = ?
                    AND EXISTS (
                        SELECT 1
                        FROM shopping_list sl
                        JOIN item i ON i.item_id = shopping_list_item.item_id
                        WHERE sl.shopping_list_id = shopping_list_item.shopping_list_id
                            AND sl.shopping_list_id = ?
                            AND sl.username = ? AND i.username = sl.username
                    )
                """, completed, shoppingListId, itemId, shoppingListId, username);
    }
}