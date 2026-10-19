package com.abcconsulting.slme.server.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@Table("shopping_list")
public record ShoppingList(
        @Id @Column("shopping_list_id") Long shoppingListId,
        @NotNull @Column("shopping_list_name") String shoppingListName,
        @NotNull @Column("username") String username,
        @Column("completed") boolean completed,
        // Set by the database (see schema.sql), never written by the application.
        @ReadOnlyProperty @Column("created_at") OffsetDateTime createdAt,
        @ReadOnlyProperty @Column("updated_at") OffsetDateTime updatedAt) {
}