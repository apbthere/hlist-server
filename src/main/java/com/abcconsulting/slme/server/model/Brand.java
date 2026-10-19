package com.abcconsulting.slme.server.model;

import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("brand")
public record Brand(
        @Id @Column("brand_id") Long brandId,
        @NotNull @Column("brand_name") String brandName) {
}