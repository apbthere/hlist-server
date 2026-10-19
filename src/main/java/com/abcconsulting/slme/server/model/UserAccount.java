package com.abcconsulting.slme.server.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import jakarta.validation.constraints.NotNull;

@Table("users")
public record UserAccount(
        @NotNull @Id @Column("username") String username,
        @NotNull @Column("password") String password,
        @NotNull @Column("enabled") Boolean enabled) {
}