package com.abcconsulting.slme.server.stores;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoreCatalogTests {

	private final StoreCatalog catalog = new StoreCatalog();

	private String match(String listName) {
		return catalog.match(listName).map(store -> store.key()).orElse(null);
	}

	@Test
	void matchesStoreNamesAsWholeWordsIgnoringCaseAndPunctuation() {
		assertEquals("costco", match("Costco"));
		assertEquals("costco", match("costco run"));
		assertEquals("publix", match("Publix Sep 30"));
		assertEquals("lowes", match("Lowe's"));
		assertEquals("lowes", match("LOWES garden stuff"));
		assertEquals("homedepot", match("The Home Depot"));
		assertEquals("homedepot", match("home   depot!"));
		assertEquals("walmart", match("Wal-Mart"));
		assertEquals("aldi", match("ALDI"));
		assertEquals("wholefoods", match("Whole Foods"));
		assertEquals("wholefoods", match("wholefoods run"));
		assertEquals("freshmarket", match("The Fresh Market"));
		assertEquals("freshmarket", match("Fresh Market Sat"));
	}

	@Test
	void prefersTheStoreMentionedFirst() {
		assertEquals("walmart", match("Walmart then Publix"));
		assertEquals("publix", match("Publix then Walmart"));
	}

	@Test
	void ignoresNamesThatOnlyContainAStoreName() {
		assertEquals(null, match("Weekly"));
		assertEquals(null, match("Aldine St pickup"));
		assertEquals(null, match("Targeted deals"));
		assertEquals(null, match("Fresh produce market"));
		assertEquals(null, match(""));
		assertEquals(null, match(null));
	}

	@Test
	void findsStoresByKey() {
		assertEquals("Home Depot", catalog.find("homedepot").orElseThrow().name());
		assertEquals(9, catalog.all().size());
	}
}
