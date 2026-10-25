package com.abcconsulting.slme.server.photos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PhotoRepository {

    private final JdbcTemplate jdbcTemplate;

    public PhotoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    long insert(String username, ImageProcessor.Processed photo) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO photo (username, image, thumbnail, width, height) VALUES (?, ?, ?, ?, ?)",
                    // Only the id: H2 would otherwise also return created_at, which the database fills in too.
                    new String[] {"photo_id"});
            statement.setString(1, username);
            statement.setBytes(2, photo.image());
            statement.setBytes(3, photo.thumbnail());
            statement.setInt(4, photo.width());
            statement.setInt(5, photo.height());
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKeyAs(Long.class));
    }

    /** The full image or thumbnail of one of the user's photos. */
    Optional<byte[]> find(long photoId, String username, boolean thumbnail) {
        List<byte[]> rows = jdbcTemplate.query(
                "SELECT " + (thumbnail ? "thumbnail" : "image") + " FROM photo WHERE photo_id = ? AND username = ?",
                (resultSet, rowNumber) -> resultSet.getBytes(1), photoId, username);
        return rows.stream().findFirst();
    }

    public boolean belongsTo(long photoId, String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM photo WHERE photo_id = ? AND username = ?", Integer.class, photoId, username);
        return count != null && count > 0;
    }

    int setItemPhoto(long itemId, String username, Long photoId) {
        return jdbcTemplate.update("UPDATE item SET photo_id = ? WHERE item_id = ? AND username = ?", photoId, itemId, username);
    }

    /** Deletes the photo if no item or favorite shows it any more. */
    public void deleteIfUnused(long photoId) {
        jdbcTemplate.update("""
                DELETE FROM photo
                WHERE photo_id = ? AND NOT EXISTS (SELECT 1 FROM item WHERE photo_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM favorite WHERE photo_id = ?)
                """, photoId, photoId, photoId);
    }

    /**
     * Deletes photos no item or favorite shows that were uploaded before the cut-off (e.g. an editor that was
     * cancelled).
     */
    int deleteUnusedOlderThan(OffsetDateTime cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM photo p
                WHERE p.created_at < ? AND NOT EXISTS (SELECT 1 FROM item i WHERE i.photo_id = p.photo_id)
                    AND NOT EXISTS (SELECT 1 FROM favorite f WHERE f.photo_id = p.photo_id)
                """, cutoff);
    }
}
