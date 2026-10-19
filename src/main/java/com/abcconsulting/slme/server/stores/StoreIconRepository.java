package com.abcconsulting.slme.server.stores;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Fetched store icons, or the fact that none could be found, in the store_icon table. */
@Repository
public class StoreIconRepository {

    /** A stored result: an icon, or (image null) the time no usable icon was found. */
    public record StoredIcon(String storeKey, byte[] image, String contentType, String sourceUrl, OffsetDateTime fetchedAt) {

        public boolean found() {
            return image != null;
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public StoreIconRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<StoredIcon> find(String storeKey) {
        List<StoredIcon> rows = jdbcTemplate.query(
                "SELECT store_key, image, content_type, source_url, fetched_at FROM store_icon WHERE store_key = ?",
                (resultSet, rowNumber) -> new StoredIcon(
                        resultSet.getString("store_key"),
                        resultSet.getBytes("image"),
                        resultSet.getString("content_type"),
                        resultSet.getString("source_url"),
                        resultSet.getObject("fetched_at", OffsetDateTime.class)),
                storeKey);
        return rows.stream().findFirst();
    }

    public void save(StoredIcon icon) {
        jdbcTemplate.update("""
                MERGE INTO store_icon (store_key, image, content_type, source_url, fetched_at)
                KEY (store_key) VALUES (?, ?, ?, ?, ?)
                """, icon.storeKey(), icon.image(), icon.contentType(), icon.sourceUrl(), icon.fetchedAt());
    }
}
