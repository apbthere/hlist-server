package com.abcconsulting.slme.server.repository;

import com.abcconsulting.slme.server.model.ShoppingList;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ShoppingListRepository extends CrudRepository<ShoppingList, Long> {
	List<ShoppingList> findAllByUsername(String username, Pageable pageable);
	long countByUsername(String username);
}