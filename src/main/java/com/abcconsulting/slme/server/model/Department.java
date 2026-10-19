package com.abcconsulting.slme.server.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import jakarta.validation.constraints.NotNull;

@Table("department")
public record Department(
        @Id @Column("department_id") Long departmentId,
        @NotNull @Column("department_name") String departmentName) {
}