package com.abcconsulting.slme.server.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.time.OffsetDateTime;

@Table("item")
public record Item(
        @Id @Column("item_id") Long itemId,
        @NotNull @Column("item_name") String itemName,
        @Min(1) @Column("quantity") Integer quantity,
        @Column("department_id") Long departmentId,
        @Column("brand_id") Long brandId,
        @Column("item_comment") String itemComment,
                @NotNull @Column("username") String username,
        // Set by the database (see schema.sql), never written by the application.
        @ReadOnlyProperty @Column("created_at") OffsetDateTime createdAt,
        @ReadOnlyProperty @Column("updated_at") OffsetDateTime updatedAt,
        // Changed only through the photo endpoints (PhotoService), so ordinary item saves never clear it.
        @ReadOnlyProperty @Column("photo_id") Long photoId) {

        public Item(
                        Long itemId,
                        String itemName,
                        Integer quantity,
                        Long departmentId,
                        Long brandId,
                        String itemComment,
                        String username) {
                this(itemId, itemName, quantity, departmentId, brandId, itemComment, username, null, null, null);
        }

        public Item(
                        Long itemId,
                        String itemName,
                        Long departmentId,
                        Long brandId,
                        String itemComment,
                        String username) {
                this(itemId, itemName, 1, departmentId, brandId, itemComment, username);
        }
}