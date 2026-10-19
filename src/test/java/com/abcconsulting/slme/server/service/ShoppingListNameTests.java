package com.abcconsulting.slme.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShoppingListNameTests {

	@Test
	void capitalizesOnlyTheFirstLetter() {
		assertEquals("Weekend shop", ShoppingListService.capitalizeFirstLetter("weekend shop"));
		assertEquals("Weekend shop", ShoppingListService.capitalizeFirstLetter("  weekend shop "));
		assertEquals("Costco run", ShoppingListService.capitalizeFirstLetter("Costco run"));
		assertEquals("IPad stuff", ShoppingListService.capitalizeFirstLetter("iPad stuff"));
		assertEquals("Ärzte", ShoppingListService.capitalizeFirstLetter("ärzte"));
		assertEquals("123 party", ShoppingListService.capitalizeFirstLetter("123 party"));
	}
}
