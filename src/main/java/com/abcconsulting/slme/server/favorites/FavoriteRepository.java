package com.abcconsulting.slme.server.favorites;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@Repository
class FavoriteRepository {

    /** What the application writes; the stores are written separately. */
    record Fields(String name, Long brandId, Long departmentId, String category, String comment, Long photoId) {
    }

    /** A favorite_store row; key is filled in by the service. */
    record StoreRow(String name, BigDecimal price, String size, String productUrl, OffsetDateTime priceCheckedAt) {
    }

    /** Cheapest first, stores without a price last, then by name. */
    static final Comparator<Favorite.StoreEntry> CHEAPEST_FIRST = Comparator
            .comparing(Favorite.StoreEntry::price, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Favorite.StoreEntry::name, String.CASE_INSENSITIVE_ORDER);

    private static final String COLUMNS = """
            favorite_id, favorite_name, brand_id, department_id, category, favorite_comment, photo_id,
            created_at, updated_at""";

    private final JdbcTemplate jdbcTemplate;

    FavoriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Favorite> findAll(String username, Function<String, String> storeKey) {
        List<Favorite> favorites = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM favorite WHERE username = ? ORDER BY favorite_id",
                (resultSet, rowNumber) -> favorite(resultSet, List.of()), username);
        Map<Long, List<Favorite.StoreEntry>> stores = new HashMap<>();
        jdbcTemplate.query("""
                SELECT fs.favorite_id, fs.store_name, fs.price, fs.package_size, fs.product_url, fs.price_checked_at
                FROM favorite_store fs
                JOIN favorite f ON f.favorite_id = fs.favorite_id
                WHERE f.username = ?
                """, (RowCallbackHandler) resultSet -> stores
                .computeIfAbsent(resultSet.getLong("favorite_id"), id -> new ArrayList<>())
                .add(store(resultSet, storeKey)), username);
        return favorites.stream().map(favorite -> withStores(favorite, stores.getOrDefault(favorite.favoriteId(), List.of())))
                .toList();
    }

    Optional<Favorite> find(long favoriteId, String username, Function<String, String> storeKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM favorite WHERE favorite_id = ? AND username = ?",
                        (resultSet, rowNumber) -> favorite(resultSet, List.of()), favoriteId, username)
                .stream().findFirst()
                .map(favorite -> withStores(favorite, jdbcTemplate.query("""
                        SELECT store_name, price, package_size, product_url, price_checked_at
                        FROM favorite_store WHERE favorite_id = ?
                        """, (resultSet, rowNumber) -> store(resultSet, storeKey), favoriteId)));
    }

    long insert(String username, Fields fields) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO favorite (username, favorite_name, brand_id, department_id, category, favorite_comment, photo_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"favorite_id"});
            statement.setString(1, username);
            setFields(statement, 2, fields);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKeyAs(Long.class));
    }

    boolean update(long favoriteId, String username, Fields fields) {
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE favorite
                    SET favorite_name = ?, brand_id = ?, department_id = ?, category = ?, favorite_comment = ?, photo_id = ?
                    WHERE favorite_id = ? AND username = ?
                    """);
            int next = setFields(statement, 1, fields);
            statement.setLong(next, favoriteId);
            statement.setString(next + 1, username);
            return statement;
        }) > 0;
    }

    boolean delete(long favoriteId, String username) {
        return jdbcTemplate.update("DELETE FROM favorite WHERE favorite_id = ? AND username = ?", favoriteId, username) > 0;
    }

    void replaceStores(long favoriteId, Collection<StoreRow> stores) {
        jdbcTemplate.update("DELETE FROM favorite_store WHERE favorite_id = ?", favoriteId);
        stores.forEach(store -> saveStore(favoriteId, store));
    }

    void saveStore(long favoriteId, StoreRow store) {
        jdbcTemplate.update("""
                MERGE INTO favorite_store (favorite_id, store_name, price, package_size, product_url, price_checked_at)
                KEY (favorite_id, store_name)
                VALUES (?, ?, ?, ?, ?, ?)
                """, favoriteId, store.name(), store.price(), store.size(), store.productUrl(), store.priceCheckedAt());
    }

    boolean deleteStore(long favoriteId, String storeName) {
        return jdbcTemplate.update("DELETE FROM favorite_store WHERE favorite_id = ? AND store_name = ?",
                favoriteId, storeName) > 0;
    }

    /** The user's categories, most used first. */
    List<String> categories(String username) {
        return jdbcTemplate.queryForList("""
                SELECT category FROM favorite
                WHERE username = ? AND category IS NOT NULL
                GROUP BY category ORDER BY COUNT(*) DESC, category
                """, String.class, username);
    }

    /** Touches the favorite so its updated_at moves when only its stores changed. */
    void touch(long favoriteId) {
        jdbcTemplate.update("UPDATE favorite SET updated_at = CURRENT_TIMESTAMP WHERE favorite_id = ?", favoriteId);
    }

    private static int setFields(PreparedStatement statement, int first, Fields fields) throws SQLException {
        statement.setString(first, fields.name());
        setLong(statement, first + 1, fields.brandId());
        setLong(statement, first + 2, fields.departmentId());
        statement.setString(first + 3, fields.category());
        statement.setString(first + 4, fields.comment());
        setLong(statement, first + 5, fields.photoId());
        return first + 6;
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Favorite favorite(ResultSet resultSet, List<Favorite.StoreEntry> stores) throws SQLException {
        return new Favorite(
                resultSet.getLong("favorite_id"),
                resultSet.getString("favorite_name"),
                resultSet.getObject("brand_id", Long.class),
                resultSet.getObject("department_id", Long.class),
                resultSet.getString("category"),
                resultSet.getString("favorite_comment"),
                resultSet.getObject("photo_id", Long.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                stores);
    }

    private static Favorite.StoreEntry store(ResultSet resultSet, Function<String, String> storeKey)
            throws SQLException {
        String name = resultSet.getString("store_name");
        return new Favorite.StoreEntry(name, storeKey.apply(name), resultSet.getBigDecimal("price"),
                resultSet.getString("package_size"), resultSet.getString("product_url"),
                resultSet.getObject("price_checked_at", OffsetDateTime.class));
    }

    private static Favorite withStores(Favorite favorite, List<Favorite.StoreEntry> stores) {
        return new Favorite(favorite.favoriteId(), favorite.name(), favorite.brandId(), favorite.departmentId(),
                favorite.category(), favorite.comment(), favorite.photoId(), favorite.createdAt(), favorite.updatedAt(),
                stores.stream().sorted(CHEAPEST_FIRST).toList());
    }
}
