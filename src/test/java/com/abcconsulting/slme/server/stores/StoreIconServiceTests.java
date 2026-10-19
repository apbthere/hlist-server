package com.abcconsulting.slme.server.stores;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreIconServiceTests {

	private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");

	/** Stands in for the store sites: returns the next prepared result and counts calls. */
	private static class FakeFetcher extends StoreIconFetcher {
		Optional<FetchedIcon> next = Optional.empty();
		final AtomicInteger calls = new AtomicInteger();

		FakeFetcher() {
			super(address -> false);
		}

		@Override
		public Optional<FetchedIcon> fetch(Store store) {
			calls.incrementAndGet();
			return next;
		}
	}

	private static class InMemoryRepository extends StoreIconRepository {
		final Map<String, StoredIcon> rows = new HashMap<>();

		InMemoryRepository() {
			super(null);
		}

		@Override
		public Optional<StoredIcon> find(String storeKey) {
			return Optional.ofNullable(rows.get(storeKey));
		}

		@Override
		public void save(StoredIcon icon) {
			rows.put(icon.storeKey(), icon);
		}
	}

	private final FakeFetcher fetcher = new FakeFetcher();
	private final InMemoryRepository repository = new InMemoryRepository();
	private final StoreIconService service = new StoreIconService(new StoreCatalog(), fetcher, repository);

	private void at(Instant instant) {
		service.setClock(Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static Optional<StoreIconFetcher.FetchedIcon> icon(int marker) {
		return Optional.of(new StoreIconFetcher.FetchedIcon(new byte[] {(byte) marker}, "image/png", URI.create("https://x/" + marker)));
	}

	@Test
	void fetchesOnceThenServesFromStorage() {
		at(START);
		fetcher.next = icon(1);
		assertArrayEquals(new byte[] {1}, service.icon("costco").orElseThrow().image());
		assertArrayEquals(new byte[] {1}, service.icon("costco").orElseThrow().image());
		assertEquals(1, fetcher.calls.get());
	}

	@Test
	void unknownStoresNeverFetch() {
		assertEquals(Optional.empty(), service.icon("not-a-store"));
		assertEquals(0, fetcher.calls.get());
	}

	@Test
	void aMissingIconIsRetriedOnlyAfterADay() {
		at(START);
		assertEquals(Optional.empty(), service.icon("aldi"));
		at(START.plus(Duration.ofHours(23)));
		assertEquals(Optional.empty(), service.icon("aldi"));
		assertEquals(1, fetcher.calls.get(), "no retry within a day");

		at(START.plus(Duration.ofHours(25)));
		fetcher.next = icon(2);
		assertTrue(service.icon("aldi").isPresent());
		assertEquals(2, fetcher.calls.get());
	}

	@Test
	void nightlyRefreshReplacesOldIconsButKeepsThemWhenTheStoreFails() {
		at(START);
		fetcher.next = icon(1);
		service.icon("publix");
		service.icon("target");

		at(START.plus(Duration.ofDays(10)));
		service.refreshStaleIcons();
		assertEquals(2, fetcher.calls.get(), "recent icons aren't re-fetched");

		at(START.plus(Duration.ofDays(31)));
		fetcher.next = Optional.empty();
		service.refreshStaleIcons();
		assertArrayEquals(new byte[] {1}, service.icon("publix").orElseThrow().image(), "a failed refresh keeps the icon");

		fetcher.next = icon(3);
		service.refreshStaleIcons();
		assertArrayEquals(new byte[] {3}, service.icon("publix").orElseThrow().image(), "a successful refresh replaces it");
	}
}
