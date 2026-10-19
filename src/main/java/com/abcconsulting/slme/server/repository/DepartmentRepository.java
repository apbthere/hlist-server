package com.abcconsulting.slme.server.repository;

import com.abcconsulting.slme.server.model.Department;
import org.springframework.data.repository.CrudRepository;

public interface DepartmentRepository extends CrudRepository<Department, Long> {
}