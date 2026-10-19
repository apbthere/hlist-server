package com.abcconsulting.slme.server.repository;

import com.abcconsulting.slme.server.model.Item;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ItemRepository extends CrudRepository<Item, Long> {
	List<Item> findAllByUsername(String username);
}